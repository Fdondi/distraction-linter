@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("OPT_IN_USAGE")

package com.timelinter.app

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_HOME
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.google.ai.client.generativeai.type.GenerateContentResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toDuration

@OptIn(ExperimentalTime::class)
class AppUsageMonitorService : Service() {
    private val TAG = "AppUsageMonitorService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var notificationManager: NotificationManager
    private lateinit var aiManager: AIInteractionManager
    private lateinit var conversationHistoryManager: ConversationHistoryManager
    private lateinit var interactionStateManager: InteractionStateManager

    // Service binding for lifecycle management
    private val binder = LocalBinder()
    private var boundClients = 0

    inner class LocalBinder : android.os.Binder() {
        fun getService(): AppUsageMonitorService = this@AppUsageMonitorService
    }

    companion object {
        // Notification Channel IDs
        const val CHANNEL_ID = "TimeLinterChannel" // Conversation Channel
        const val STATS_CHANNEL_ID = "TimeLinterStatsChannel" // Stats Channel
        // Notification IDs
        const val NOTIFICATION_ID = 1 // Conversation Notification
        const val STATS_NOTIFICATION_ID = 2 // Persistent Stats Notification
        
        // Intent Actions & Extras
        const val ACTION_HANDLE_REPLY = "com.example.timelinter.ACTION_HANDLE_REPLY"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_APP_NAME = "com.example.timelinter.EXTRA_APP_NAME"
        const val EXTRA_SESSION_TIME_MS = "com.example.timelinter.EXTRA_SESSION_TIME_MS"
        const val EXTRA_DAILY_TIME_MS = "com.example.timelinter.EXTRA_DAILY_TIME_MS"
        
        // Modern way to track if service is running (replaces getRunningServices)
        @Volatile
        private var serviceInstance: AppUsageMonitorService? = null
        
        /**
         * Check if the service is currently running.
         * This is the modern replacement for getRunningServices().
         */
        fun isServiceRunning(): Boolean {
            return serviceInstance != null
        }
        
        /**
         * Get the current service instance if running, null otherwise.
         * Internal use only.
         */
        internal fun getServiceInstance(): AppUsageMonitorService? = serviceInstance
    }

    // Timings
    private val statsUpdateIntervalSeconds = 30L // How often to update the stats notification
    private val checkIntervalSeconds = 10L // How often to check app usage for time tracking

    private var timeProvider: TimeProvider = SystemTimeProvider

    private var sessionStart: Instant = timeProvider.now()
    private var sessionWastedTime: Duration = Duration.ZERO
    private var dailyWastedTime: Duration = Duration.ZERO
    private var currentApp: String = ""
    private var lastAppChangeTime: Instant = timeProvider.now()
    private var currentAppSessionStart: Instant = timeProvider.now() // Track when current app session started
    private val freeExpiryByApp: MutableMap<String, Instant> = mutableMapOf()
    private val freeAllowancePrefs by lazy { getSharedPreferences("free_time_allowance_usage", Context.MODE_PRIVATE) }
    private var freeAllowanceDay: String = currentDay()

    // Unified token bucket - owns its own state
    private lateinit var tokenBucket: TokenBucket

    // Coroutine scope for background tasks like API calls
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Monitoring state
    private val isMonitoringScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var mainLoopFuture: ScheduledFuture<*>? = null
    private var statsFuture: ScheduledFuture<*>? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    // Persons for MessagingStyle
    private lateinit var userPerson: Person
    private lateinit var aiPerson: Person

    // State tracking
    @Volatile private var wasPreviouslyWasteful = false
    @Volatile private var authExpired = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        // Register this instance as the running service
        serviceInstance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        
        // Initialize InteractionStateManager
        interactionStateManager = InteractionStateManager(this)
        
        // Initialize ConversationHistoryManager
        conversationHistoryManager = ConversationHistoryManager(
            context = this,
            systemPrompt = readRawResource(R.raw.gemini_system_prompt),
            aiMemoryTemplate = readRawResource(R.raw.ai_memory_template),
            userInfoTemplate = readRawResource(R.raw.user_info_template),
            userInteractionTemplate = readRawResource(R.raw.gemini_user_template),
            timeProvider = timeProvider
        )
        
        // Then initialize AIInteractionManager with the conversationHistoryManager
        aiManager = AIInteractionManager(this, conversationHistoryManager)

        // Initialize Persons
        userPerson = Person.Builder().setName("You").setKey("user").build()
        val coachName = ApiKeyManager.getCoachName(this)
        aiPerson = Person.Builder().setName(coachName).setKey("ai").setBot(true).build()

        // Initialize token bucket - it will handle its own state
        tokenBucket = TokenBucket(this, timeProvider)
        EventLogStore.setContextLineProvider { buildLogContextLine() }

        Log.d(TAG, "Starting foreground service with notification ID: $STATS_NOTIFICATION_ID")
        try {
            startForeground(STATS_NOTIFICATION_ID, createStatsNotification())
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startForeground", e)
        }

        // Register screen/lock state receiver
        registerScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_HANDLE_REPLY -> {
                handleReply(intent)
            }
            else -> {
                Log.d(TAG, "onStartCommand: Default action - ensuring monitoring is started.")
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isMonitoringScheduled.get()) {
             Log.d(TAG, "startMonitoring: Monitoring already scheduled. Skipping.")
             return
        }
        
        Log.d(TAG, "startMonitoring: Scheduling tasks. Check: $checkIntervalSeconds s, Stats: $statsUpdateIntervalSeconds s")
        try {
            // Schedule the main interaction loop
            mainLoopFuture = executor.scheduleWithFixedDelay({
                try {
                    Log.v(TAG, "Scheduled task running: mainInteractionLoop")
                    mainInteractionLoop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error within mainInteractionLoop task", t)
                }
            }, 0, checkIntervalSeconds, TimeUnit.SECONDS)

            // Schedule stats notification update less frequently
             statsFuture = executor.scheduleWithFixedDelay({
                 try {
                    Log.d(TAG, "Scheduled task running: updateStatsNotification")
                    updateStatsNotification()
                 } catch (t: Throwable) {
                    Log.e(TAG, "Error within updateStatsNotification task", t)
                 }
             }, 5, statsUpdateIntervalSeconds, TimeUnit.SECONDS)
             
             isMonitoringScheduled.set(true)
             Log.i(TAG, "Monitoring tasks scheduled successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling monitoring tasks", e)
             isMonitoringScheduled.set(false)
        }
    }

    private fun stopMonitoringTasks(reason: String) {
        try {
            mainLoopFuture?.cancel(false)
            statsFuture?.cancel(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling scheduled tasks", e)
        } finally {
            mainLoopFuture = null
            statsFuture = null
            isMonitoringScheduled.set(false)
            Log.i(TAG, "Monitoring tasks stopped (${reason}).")
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen turned off; stopping monitoring tasks and clearing current app")
                        if (isMonitoringScheduled.get()) stopMonitoringTasks("screen off")
                        // Clear current app so that next unlock doesn't reuse stale context
                        currentApp = ""
                        // Reset interaction state to observing to avoid mid-conversation surprises
                        interactionStateManager.resetToObserving()
                        // Cancel any in-flight conversation notification
                        try { notificationManager.cancel(NOTIFICATION_ID) } catch (_: Throwable) {}
                    }
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_UNLOCKED -> {
                        // Only resume if truly unlocked and interactive
                        if (!isDeviceLockedOrScreenOff()) {
                            Log.i(TAG, "User present/screen on; attempting to start monitoring")
                            startMonitoring()

                            // Schedule immediate check with a small delay to avoid race conditions
                            // This ensures monitoring tasks are properly started before running the first check
                            serviceScope.launch {
                                kotlinx.coroutines.delay(100) // Small delay to ensure monitoring is fully started
                                try {
                                    Log.d(TAG, "Running immediate check after unlock")
                                    mainInteractionLoop()
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Error during immediate unlock check", t)
                                }
                            }
                        } else {
                            Log.v(TAG, "Received $action but device still locked or screen off; not starting")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        try {
            registerReceiver(screenStateReceiver, filter)
            Log.d(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering screen state receiver", e)
        }
    }

    /**
     * Main interaction loop implementing the flow from interaction.md
     */
    private fun mainInteractionLoop() {
        // Clean up expired allows
        interactionStateManager.cleanupExpiredAllows()
        
        // Get current app usage
        val detectedInfo = getCurrentAppUsage()
        // Probabilistic fallback: if detection is empty, 75% chance keep previous app, 25% clear it
        // This handles transient empty detections while eventually clearing truly idle states
        val effectiveInfo = detectedInfo ?: run {
            if (Random().nextInt(4) == 0) {
                // 1 in 4 chance: clear the app
                null
            } else {
                // 3 in 4 chance: keep previous app
                getAppInfoFromCurrentApp()
            }
        }
        updateTimeTracking(effectiveInfo)
        
        val stateName = interactionStateManager.getStateName()
        Log.v(TAG, "Main loop: State=$stateName, App=${effectiveInfo?.packageName}, Wasteful=${effectiveInfo?.isWasteful}")
        
        // Use minimal API methods instead of pattern matching on internal state
        when {
            interactionStateManager.isInObservingState() -> {
                handleObservingState(effectiveInfo)
            }
            interactionStateManager.isInConversationState() -> {
                handleConversationActiveState(effectiveInfo)
            }
            interactionStateManager.isWaitingForResponse() -> {
                handleWaitingForResponseState(effectiveInfo)
            }
        }
    }

    private fun handleObservingState(appInfo: AppInfo?) {
        // Step 0-1 from interaction.md
        
        val isWasteful = appInfo?.isWasteful == true
        val isAllowed = if (appInfo != null) interactionStateManager.isAllowed(appInfo.readableName) else false
        val remaining = tokenBucket.getCurrentRemaining()
        if (!TriggerDecider.shouldTrigger(isWasteful, isAllowed, remaining)) {
            return
        }

        // Threshold exceeded - start conversation
        Log.i(TAG, "Threshold exceeded for ${appInfo?.readableName ?: "Unknown"}, starting conversation (bucket empty)")
        
        // Step 1b: Create initial AI history and start conversation
        conversationHistoryManager.startNewSession(
            appName = appInfo?.readableName ?: "Unknown App",
            sessionTime = sessionWastedTime,
            dailyTime = dailyWastedTime
        )
        
        interactionStateManager.startConversation()
        
        // Step 2: Send to AI and get initial response (FIRST_MESSAGE)
        generateAIResponse(appInfo, AITask.FIRST_MESSAGE)
    }

    private fun handleConversationActiveState(appInfo: AppInfo?) {
        // Check if user moved away from wasteful apps
        if (appInfo?.isWasteful != true) {
            Log.i(TAG, "User moved away from wasteful apps, resetting to observing")
            interactionStateManager.resetToObserving()
            return
        }
        
        // Still on wasteful app, but conversation is active
        // This state is handled by user responses or timeouts
        Log.v(TAG, "Conversation active, waiting for user interaction")
    }

    private fun handleWaitingForResponseState(appInfo: AppInfo?) {
        // Step 6a/6b from interaction.md
        
        // Check if user moved away from wasteful apps
        if (appInfo?.isWasteful != true) {
            Log.i(TAG, "User moved away from wasteful apps during response wait, resetting")
            interactionStateManager.resetToObserving()
            return
        }
        
        // From this point we know appInfo is non-null and wasteful
        val nonNullAppInfo = appInfo

        // Check for timeout
        if (interactionStateManager.isResponseTimedOut()) {
            Log.i(TAG, "Response timeout - adding '*no response*' and continuing")
            
            // Step 6a: Add "*no response*" to AI conversation (decorated), not UI
            conversationHistoryManager.addNoResponseMessage(
                currentAppName = nonNullAppInfo.readableName,
                sessionTime = sessionWastedTime,
                dailyTime = dailyWastedTime
            )
            
            // Continue conversation
            interactionStateManager.continueConversation()
            
            // Step 7: Go to step 2 - send to AI again (FOLLOWUP_NO_RESPONSE)
            generateAIResponse(appInfo, AITask.FOLLOWUP_NO_RESPONSE)
        }
    }

    private fun generateAIResponse(appInfo: AppInfo?, task: AITask) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Sending to AI using task: ${task.displayName}")

                val parsedResponse = aiManager.generateResponse(task)
                
                if (parsedResponse == null) {
                     Log.e(TAG, "AI Response was null")
                     return@launch
                }

                if (authExpired != parsedResponse.authExpired) {
                    authExpired = parsedResponse.authExpired
                    updateStatsNotification()
                }

                // Process tools
                var shouldResetConversation = false
                for (tool in parsedResponse.tools) {
                    when (tool) {
                        is ToolCommand.Allow -> {
                            Log.i(TAG, "Processing ALLOW tool: ${tool.duration} extra time for app ${tool.app?.let { " for $it" } ?: ""}")
                            interactionStateManager.applyAllowCommand(tool)
                            // Log tool usage to API history only
                            conversationHistoryManager.addToolLog(tool)
                            shouldResetConversation = true
                        }
                        is ToolCommand.Remember -> {
                            Log.i(TAG, "Processing REMEMBER tool: ${tool.content}")
                            val tmp: Duration? = tool.duration
                            if (tmp != null) {
                                AIMemoryManager.addTemporaryMemory(this@AppUsageMonitorService, tool.content, tmp)
                            } else {
                                AIMemoryManager.addPermanentMemory(this@AppUsageMonitorService, tool.content)
                            }
                            // Log tool usage to API history only
                            conversationHistoryManager.addToolLog(tool)
                            // Update memory in UI store immediately
                            ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(this@AppUsageMonitorService))
                        }
                    }
                }

                // Step 4: If there was any non-tool answer,  display to user
                if (parsedResponse.userMessage.isNotEmpty()) {
                    conversationHistoryManager.addAIMessage(parsedResponse.userMessage)
                    showConversationNotification(
                        appName = appInfo?.readableName ?: "Unknown App",
                        sessionTime = sessionWastedTime,
                        dailyTime = dailyWastedTime
                    )
                }

                // Step 5a/5b: Check if conversation should reset or continue
                if (shouldResetConversation || parsedResponse.userMessage.isEmpty()) {
                    // Step 5a: ALLOW tool used or no non-tool message -> archive and reset
                    Log.d(TAG, "Resetting conversation (ALLOW used or no message) - archiving first")
                    tryArchiveMemoriesThenClear(appInfo)
                    interactionStateManager.resetToObserving()
                    
                    // Cancel the conversation notification since history is cleared
                    notificationManager.cancel(NOTIFICATION_ID)
                    Log.d(TAG, "Cancelled conversation notification due to conversation reset")
                    
                    // Update stats notification to show current app time now that conversation is cleared
                    updateStatsNotification()
                } else {
                    // Step 5b: Continue conversation, wait for response
                    Log.d(TAG, "Continuing conversation, waiting for user response")
                    interactionStateManager.startWaitingForResponse()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error generating AI response", e)
            }
        }
    }

    private fun handleReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()
            if (!replyText.isNullOrEmpty()) {
                Log.i(TAG, "User replied: '$replyText'")
                
                val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Unknown App"
                val sessionTime = intent.getLongExtra(EXTRA_SESSION_TIME_MS, 0L).toDuration(DurationUnit.MILLISECONDS)
                val dailyTime = intent.getLongExtra(EXTRA_DAILY_TIME_MS, 0L).toDuration(DurationUnit.MILLISECONDS)

                // Step 6b: User answered, save response and continue
                conversationHistoryManager.addUserMessage(replyText, appName, sessionTime, dailyTime)
                interactionStateManager.continueConversation()
                
                // Step 7: Go to step 2 - send to AI (USER_RESPONSE)
                val currentAppInfo = getCurrentAppUsage()
                generateAIResponse(currentAppInfo, AITask.USER_RESPONSE)

            } else { 
                Log.w(TAG, "Received empty reply.") 
            }
        } else { 
            Log.w(TAG, "Could not extract remote input from reply intent.") 
        }
    }

    private data class AppInfo(
        val packageName: String,
        val readableName: String,
        val isWasteful: Boolean,
        val isGoodApp: Boolean = false
    )

    private fun getAppInfoFromCurrentApp(): AppInfo? {
        val pkg = currentApp.takeIf { it.isNotEmpty() } ?: return null
        val readable = getReadableAppName(pkg)
        val wasteful = isWastefulApp(pkg)
        val goodApp = GoodAppManager.isGoodApp(applicationContext, pkg)
        return AppInfo(packageName = pkg, readableName = readable, isWasteful = wasteful, isGoodApp = goodApp)
    }

    private fun getCurrentAppUsage(): AppInfo? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 60 * 1 // 1 minute

        try {
            // Check if user is unlocked (required for Android R+)
            val userManager = getSystemService(USER_SERVICE) as UserManager
            if (!userManager.isUserUnlocked) {
                Log.w(TAG, "User is locked, cannot query usage events")
                return null
            }

            // If device is locked or screen is not interactive, treat as no foreground app
            if (isDeviceLockedOrScreenOff()) {
                Log.v(TAG, "Device locked or screen off; returning no current app")
                return null
            }

            // Try queryEvents first (more real-time, better for Android 15)
            var currentForegroundApp: String? = null
            
            try {
                val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
                if (usageEvents != null) {
                    val event = android.app.usage.UsageEvents.Event()
                    
                    // Process events chronologically to find the most recent foreground app
                    while (usageEvents.hasNextEvent()) {
                        usageEvents.getNextEvent(event)
                        
                        // Filter out our own app and empty package names
                        if (event.packageName == applicationContext.packageName || event.packageName.isEmpty()) {
                            continue
                        }
                        
                        // Look for ACTIVITY_RESUMED events (app comes to foreground)
                        if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                            currentForegroundApp = event.packageName
                            Log.v(TAG, "Found ACTIVITY_RESUMED event for: ${event.packageName}")
                        } else if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED) {
                            // If the same app that was in foreground gets paused, it's no longer foreground
                            if (event.packageName == currentForegroundApp) {
                                Log.v(TAG, "Found ACTIVITY_PAUSED event for current foreground app: ${event.packageName}")
                                currentForegroundApp = null
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "queryEvents returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with queryEvents, trying queryUsageStats fallback", e)
            }
            
            // Fallback: Use queryUsageStats if queryEvents didn't work (better compatibility)
            if (currentForegroundApp == null) {
                try {
                    val usageStats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_BEST,
                        beginTime,
                        endTime
                    )
                    
                    if (usageStats != null && usageStats.isNotEmpty()) {
                        // Sort by last time used and get the most recent
                        val sortedStats = usageStats.sortedByDescending { it.lastTimeUsed }
                        val mostRecent = sortedStats.firstOrNull()
                        if (mostRecent != null && mostRecent.packageName != applicationContext.packageName) {
                            currentForegroundApp = mostRecent.packageName
                            Log.v(TAG, "Fallback: Found app via queryUsageStats: ${mostRecent.packageName}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with queryUsageStats fallback", e)
                }
            }
            
            // If we found a foreground app, return its info unless it's the launcher/home
            if (currentForegroundApp != null) {
                Log.d(TAG, "Found foreground app package: $currentForegroundApp")
                
                if (isHomeLauncher(currentForegroundApp)) {
                    Log.v(TAG, "Foreground is launcher; treating as no app")
                    return null
                }
                val readableName = getReadableAppName(currentForegroundApp)
                val isWasteful = isWastefulApp(currentForegroundApp)
                val isGoodApp = GoodAppManager.isGoodApp(applicationContext, currentForegroundApp)
                
                // Debug logging for app detection
                Log.d(TAG, "Detected app: $currentForegroundApp ($readableName) - Wasteful: $isWasteful, Good: $isGoodApp")
                val selectedApps = TimeWasterAppManager.getSelectedApps(applicationContext)
                Log.d(TAG, "Selected wasteful apps: $selectedApps")
                if (!isWasteful && selectedApps.isNotEmpty()) {
                    Log.w(TAG, "App $currentForegroundApp is NOT in selected apps list. Checking if it's X/Twitter with different package name...")
                    // Check for common X/Twitter package name variations
                    val xVariations = listOf("com.twitter.android", "com.x.android", "com.twitter", "twitter")
                    val isX = xVariations.any { currentForegroundApp.contains(it, ignoreCase = true) }
                    if (isX) {
                        Log.w(TAG, "Detected X/Twitter app but it's not in selected list. Make sure you selected the correct package name: $currentForegroundApp")
                    }
                }
                
                return AppInfo(
                    packageName = currentForegroundApp,
                    readableName = readableName,
                    isWasteful = isWasteful,
                    isGoodApp = isGoodApp
                )
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error querying usage events", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage events", e)
        }
        
        return null
    }

    private fun updateTimeTracking(appInfo: AppInfo?) {
        val now = timeProvider.now()
        ensureAllowanceDay(now)
        val detectedApp = appInfo?.packageName ?: ""
        val freeActive = appInfo?.packageName?.let { isInFreeWindow(it, now) } == true
        val isCurrentlyWasteful = appInfo?.isWasteful == true && !freeActive
        val isCurrentlyGoodApp = appInfo?.isGoodApp == true

        // Determine the current app state
        val currentAppState = when {
            isCurrentlyWasteful -> AppState.WASTEFUL
            isCurrentlyGoodApp -> AppState.GOOD
            else -> AppState.NEUTRAL
        }

        // ---------------- Token bucket update ----------------
        // The TokenBucket now owns its state and fetches current settings automatically
        val oldRemaining = tokenBucket.getCurrentRemaining()
        
        // Only update bucket if app state changed OR if we're in WASTEFUL state (to continuously drain)
        // This prevents bucket from updating unnecessarily when in neutral/good states
        val newRemaining = tokenBucket.update(appState = currentAppState)
        
        // Log bucket updates for debugging with more detail
        if (oldRemaining != newRemaining) {
            val delta = oldRemaining - newRemaining
            Log.d(TAG, "Bucket updated: ${oldRemaining.inWholeSeconds} s -> ${newRemaining.inWholeSeconds} s (delta: ${delta.inWholeSeconds} s, state: $currentAppState, app: ${appInfo?.readableName ?: "none"})")
        }

        // If bucket just transitioned from empty (<=0) to full (== max), archive & clear
        val becameFull = newRemaining >= SettingsManager.getMaxThreshold(this)
        if (becameFull && isCurrentlyWasteful) {
            Log.i(TAG, "Token bucket refilled to full while wasteful: archiving and clearing conversation.")
            EventLogStore.logBucketRefilledAndReset()
            tryArchiveMemoriesThenClear(appInfo)
            interactionStateManager.resetToObserving()
            
            // Cancel the conversation notification since history is cleared
            notificationManager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Cancelled conversation notification due to bucket refill and history clear")
            
            // Update stats notification to show current app time now that conversation is cleared
            updateStatsNotification()
        }

        // ---------------- Existing time tracking logic ----------------

        // App change detection and time accumulation logic
        if (detectedApp != currentApp) {
             Log.d(TAG, "App changed from '$currentApp' to '$detectedApp'")
             
             // Clear any active free window for previous app
             freeExpiryByApp.remove(currentApp)

             // Only log meaningful transitions (not "None → None")
             val previousApp = if (currentApp.isNotEmpty()) currentApp else "None"
             val newApp = if (detectedApp.isNotEmpty()) detectedApp else "None"
             if (previousApp != newApp) {
                 EventLogStore.logAppChanged(currentApp, detectedApp)
             }
             
             // Add time spent on previous app IF IT WAS WASTEFUL
             if (currentApp.isNotEmpty()) {
                 if (isWastefulApp(currentApp)) {
                     val timeSpent = now - lastAppChangeTime
                     if (timeSpent < 5.minutes) { 
                         sessionWastedTime += timeSpent
                         dailyWastedTime += timeSpent
                         Log.d(TAG, "Added $timeSpent to wasteful time (previous app: $currentApp)")
                     } else {
                          Log.w(TAG, "Ignoring large time difference ($timeSpent) for previous app: $currentApp")
                     }
                 }
             }

            // Update currentApp; if detection is empty, clear current app
            val appActuallyChanged = currentApp != detectedApp
            currentApp = detectedApp
            lastAppChangeTime = now
            
            // Only reset currentAppSessionStart when app actually changes
            if (appActuallyChanged && detectedApp.isNotEmpty()) {
                currentAppSessionStart = now
            }

             // Reset session timer ONLY when a *new* wasteful app starts
             if (isWastefulApp(detectedApp)) {
                  sessionWastedTime = Duration.ZERO
                  sessionStart = now
                  Log.d(TAG, "New wasteful app detected: $detectedApp. Reset session timer.")
                  EventLogStore.logNewWastefulAppDetected(detectedApp)
                  startFreeAllowanceIfEligible(detectedApp, now)
             }
         } else if (isCurrentlyWasteful) {
             // Accumulate time for current wasteful app
             val timeSpent = now - lastAppChangeTime
              if (timeSpent < 5.minutes) {
                 sessionWastedTime += timeSpent
                 dailyWastedTime += timeSpent
                 Log.v(TAG, "Accumulated $timeSpent for current wasteful app: $currentApp")
              }
             lastAppChangeTime = now
         }
         
        wasPreviouslyWasteful = isCurrentlyWasteful
    }

    private fun tryArchiveMemoriesThenClear(appInfo: AppInfo?) {
        // No explicit check for model initialization; aiManager handles it
        // and returns null/error if not ready.

        val appName = appInfo?.readableName ?: "Unknown App"
        // Add API-only user message asking for memory suggestions
        val prompt = "This conversation is to be archived. If there's anything worth remembering that wasn't already in the AI memory at the beginning of this conversation, call remember(content) with each important fact. If there's nothing new to remember, simply acknowledge without calling any function."
        conversationHistoryManager.addApiOnlyUserMessage(
            messageText = prompt,
            currentAppName = appName,
            sessionTime = sessionWastedTime,
            dailyTime = dailyWastedTime
        )

        // Build contents for a one-off generation using the updated API history
        val contents = conversationHistoryManager.getHistoryForAPI()
        val context = this
        
        serviceScope.launch {
            // Use SUMMARY task for archiving
            val parsedResponse = aiManager.generateFromContents(contents, AITask.SUMMARY)
            
            if (parsedResponse != null) {
                // Process any remember() function calls
                for (tool in parsedResponse.tools) {
                    if (tool is ToolCommand.Remember) {
                        val duration = tool.duration
                        if (duration != null) {
                            AIMemoryManager.addTemporaryMemory(context, tool.content, duration, timeProvider)
                        } else {
                            AIMemoryManager.addPermanentMemory(context, tool.content)
                        }
                        Log.i(TAG, "Archived memory from conversation: ${tool.content}")
                    }
                }
                
                // Update memory shown in log screen
                ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
            }
            // Finally clear histories for a new session
            conversationHistoryManager.clearHistories()
        }
    }

    private fun isWastefulApp(packageName: String): Boolean {
        return TimeWasterAppManager.isTimeWasterApp(applicationContext, packageName)
    }

    private fun showConversationNotification(appName: String = "Unknown App", sessionTime: Duration = Duration.ZERO, dailyTime: Duration = Duration.ZERO) {
        Log.d(TAG, "showConversationNotification called. History size: ${conversationHistoryManager.getUserVisibleHistory().size}")
        // Never show conversation notifications when device is locked or screen off
        if (isDeviceLockedOrScreenOff()) {
            Log.w(TAG, "Suppressing conversation notification: device locked or screen off")
            return
        }

        if (conversationHistoryManager.getUserVisibleHistory().isEmpty()) {
             Log.w(TAG, "showConversationNotification called with empty history. No notification to show.")
             return
        }

        // Remote Input Action with Context
        val remoteInput: RemoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run { setLabel("Reply...").build() }
        val replyIntent = Intent(this, AppUsageMonitorService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_SESSION_TIME_MS, sessionTime.inWholeMilliseconds)
            putExtra(EXTRA_DAILY_TIME_MS, dailyTime.inWholeMilliseconds)
        }
        val replyPendingIntent: PendingIntent = PendingIntent.getService(this, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action: NotificationCompat.Action = NotificationCompat.Action.Builder(R.mipmap.ic_launcher, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Build MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
        conversationHistoryManager.getUserVisibleHistory().forEach { msg ->
            val notificationMessage = NotificationCompat.MessagingStyle.Message(
                msg.text, msg.timestamp, msg.sender
            )
            messagingStyle.addMessage(notificationMessage)
        }
        
        // Build Notification
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .addAction(action)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentTitle(appName)

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
            Log.d(TAG, "Conversation notification sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending conversation notification", e)
        }
    }

    private fun updateStatsNotification() {
        try {
            notificationManager.notify(STATS_NOTIFICATION_ID, createStatsNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stats notification", e)
        }
    }

    private fun createStatsNotification(): android.app.Notification {
        val monitoringStatus = "Monitoring: Active"
        val currentAppName = if (currentApp.isNotEmpty()) {
            val appName = getReadableAppName(currentApp)
            val wasteful = isWastefulApp(currentApp)
            val goodApp = GoodAppManager.isGoodApp(applicationContext, currentApp)
            val emoji = when {
                wasteful -> "💀"
                goodApp -> "🌸"
                else -> "?"
            }
            "$appName $emoji"
        } else {
            "(no app detected)"
        }
        val maxThreshold = SettingsManager.getMaxThreshold(this)
        val bucketRemaining = tokenBucket.getCurrentRemaining()
        val now = timeProvider.now()
        val freeActive = currentApp.isNotEmpty() && isInFreeWindow(currentApp, now)
        
        // Always show the time, then add "full" if at max and won't increase without good apps
        val isAtMax = bucketRemaining >= maxThreshold
        val isWasteful = currentApp.isNotEmpty() && isWastefulApp(currentApp)
        val isGood = currentApp.isNotEmpty() && GoodAppManager.isGoodApp(applicationContext, currentApp)
        val willNotIncrease = isAtMax && !isGood // Won't increase if at max and not using good app
        val bucketInUse = isWasteful && !freeActive
        
        val bucketText = buildString {
            append("Bucket: ${formatDuration(bucketRemaining)}")
            if (bucketInUse) {
                append(" (draining)")
            } else if (freeActive) {
                append(" (free time)")
            }
            if (willNotIncrease) {
                append(" (full)")
            } else if (isAtMax && isGood) {
                val overfillAmount = bucketRemaining - maxThreshold
                if (overfillAmount.isPositive()) {
                    append(" (full +${formatDuration(overfillAmount)} overfill)")
                } else {
                    append(" (full)")
                }
            }
        }
        
        // Calculate current app time when on a wasteful app
        // Use currentAppSessionStart to show total time on current app (not reset by accumulation cycles)
        val currentAppTime = if (isWasteful && currentApp.isNotEmpty()) {
            val timeSpent = now - currentAppSessionStart
            // Always show current app time, no matter how long
            "Current App Time: ${formatDuration(timeSpent)}"
        } else null
        val freeTimeRemaining = if (freeActive) {
            val expiry = freeExpiryByApp[currentApp]
            val remaining = expiry?.let { it - now }
            if (remaining != null && remaining.isPositive()) {
                "Free time left: ${formatDuration(remaining)}"
            } else null
        } else null
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = if (authExpired) R.drawable.ic_warning_sign else R.mipmap.ic_launcher
        val authFooter = if (authExpired) "Google login expired - tap to re-login" else null

        Log.d(TAG, "Creating stats notification. App: $currentAppName (package: $currentApp), Session: ${sessionWastedTime}, Daily: ${dailyWastedTime}, Bucket: ${bucketRemaining}")
        
        // Build notification text with current app time when bucket is being consumed
        val notificationText = buildString {
            appendLine(monitoringStatus)
            appendLine("Current: $currentAppName")
            // Show package name for debugging
            if (currentApp.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appendLine("Pkg: ${currentApp.substringAfterLast('.')}")
            }
            if (currentAppTime != null) {
                appendLine(currentAppTime)
            }
            if (freeTimeRemaining != null) {
                appendLine(freeTimeRemaining)
            }
            appendLine("Session Time: ${formatDuration(sessionWastedTime)}")
            appendLine("Daily Time: ${formatDuration(dailyWastedTime)}")
            appendLine(bucketText)
            authFooter?.let { appendLine(it) }
        }

        val builder = NotificationCompat.Builder(this, STATS_CHANNEL_ID)
            .setContentTitle("Time Linter")
            .setContentText(authFooter ?: monitoringStatus)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(notificationText)
                 .setSummaryText("Time Linter Stats")
            )
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_MIN) 
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null) 
            .setVibrate(longArrayOf(0L))
            .setContentIntent(contentIntent)

        if (authFooter != null) {
            builder.setSubText(authFooter)
        }
            
        return builder.build()
    }

    private fun createNotificationChannels() {
        // Conversation Channel (High Priority)
        val conversationChannel = NotificationChannel(
            CHANNEL_ID,
            "Conversation",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Time Linter conversation notifications"
            enableVibration(true)
            setShowBadge(true)
        }

        // Stats Channel (Low Priority)
        val statsChannel = NotificationChannel(
            STATS_CHANNEL_ID,
            "Statistics",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Time Linter statistics and monitoring status"
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
        }

        notificationManager.createNotificationChannels(listOf(conversationChannel, statsChannel))
        Log.d(TAG, "Notification channels created")
    }

    // ---------- Device/launcher helpers ----------
    private fun isDeviceLockedOrScreenOff(): Boolean {
        return try {
            val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val locked = keyguard.isKeyguardLocked
            val interactive = power.isInteractive
            locked || !interactive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device lock/screen state", e)
            false
        }
    }

    private fun isHomeLauncher(packageName: String): Boolean {
        return try {
            val intent = Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val homePkg = resolveInfo?.activityInfo?.packageName
            packageName == homePkg
        } catch (e: Exception) {
            Log.e(TAG, "Error determining home launcher", e)
            false
        }
    }

    private fun getReadableAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // Fallback to common app names
            when (packageName) {
                "com.facebook.katana" -> "Facebook"
                "com.instagram.android" -> "Instagram"
                "com.twitter.android" -> "Twitter"
                "com.google.android.youtube" -> "YouTube"
                "com.netflix.mediaclient" -> "Netflix"
                "com.reddit.frontpage" -> "Reddit"
                "com.tiktok.android" -> "TikTok"
                "com.snapchat.android" -> "Snapchat"
                "com.pinterest" -> "Pinterest"
                "com.linkedin.android" -> "LinkedIn"
                else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
    }

    // ---------- Free allowance helpers ----------
    private fun currentDay(now: Instant = timeProvider.now()): String {
        val javaInstant = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
        return javaInstant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    private fun ensureAllowanceDay(now: Instant = timeProvider.now()) {
        val today = currentDay(now)
        if (today != freeAllowanceDay) {
            freeAllowanceDay = today
            freeExpiryByApp.clear()
            freeAllowancePrefs.edit().clear().apply()
        }
    }

    private fun getUsesUsedToday(packageName: String): Int {
        return freeAllowancePrefs.getInt("uses_${freeAllowanceDay}_$packageName", 0)
    }

    private fun incrementUses(packageName: String) {
        val key = "uses_${freeAllowanceDay}_$packageName"
        val current = freeAllowancePrefs.getInt(key, 0)
        freeAllowancePrefs.edit().putInt(key, current + 1).apply()
    }

    private fun isInFreeWindow(packageName: String, now: Instant): Boolean {
        val expiry = freeExpiryByApp[packageName] ?: return false
        if (now < expiry) return true
        freeExpiryByApp.remove(packageName)
        return false
    }

    private fun startFreeAllowanceIfEligible(packageName: String, now: Instant) {
        val allowance = TimeWasterAppManager.getFreeAllowance(this, packageName, 10.minutes, 4)
        val minutes = allowance.first
        val usesPerDay = allowance.second
        if (minutes <= Duration.ZERO || usesPerDay <= 0) return
        val used = getUsesUsedToday(packageName)
        if (used >= usesPerDay) return
        incrementUses(packageName)
        val expiry = now + minutes
        freeExpiryByApp[packageName] = expiry
        Log.i(TAG, "Free allowance started for $packageName: $minutes min (use ${used + 1}/$usesPerDay) until $expiry")
    }

    private fun buildLogContextLine(now: Instant = timeProvider.now()): String {
        val bucketRemaining = tokenBucket.getCurrentRemaining()
        val freeActive = currentApp.isNotEmpty() && isInFreeWindow(currentApp, now)
        val freeRemaining = if (freeActive) {
            freeExpiryByApp[currentApp]?.let { expiry ->
                val remaining = expiry - now
                if (remaining.isPositive()) formatDuration(remaining) else "0 s"
            }
        } else null
        val freeLabel = if (freeActive) {
            if (freeRemaining != null) "active ($freeRemaining)" else "active"
        } else {
            "inactive"
        }
        return "Context: bucket=${formatDuration(bucketRemaining)} free=$freeLabel"
    }
    private fun formatDuration(duration: Duration): String {
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        val seconds = duration.inWholeSeconds % 60
        val res = buildString {
            if (hours > 0) {
                append("$hours h ")
            }
            if (minutes > 0) {
                append("$minutes m ")
            }
            // Always show seconds, even if 0, to ensure we always display something
            append("$seconds s")
        }
        // Always return at least "0 s" if duration is zero or very small
        return res.ifEmpty { "0 s" }
    }

    private fun readRawResource(resourceId: Int): String {
        return try {
            resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw resource $resourceId", e)
            ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        // Clear the service instance reference
        serviceInstance = null
        // Persist bucket value so it survives service restarts
        tokenBucket.persistCurrentState()
        serviceScope.cancel()
        executor.shutdown()
        isMonitoringScheduled.set(false)
        EventLogStore.setContextLineProvider(null)
        
        // Cancel all notifications when service is destroyed
        notificationManager.cancelAll()
        Log.d(TAG, "Cancelled all notifications on service destroy")
        
        // Unregister receiver
        try {
            if (screenStateReceiver != null) {
                unregisterReceiver(screenStateReceiver)
                screenStateReceiver = null
                Log.d(TAG, "Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering screen state receiver", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        Log.d(TAG, "Service bound, total clients: $boundClients")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients--
        Log.d(TAG, "Service unbound, total clients: $boundClients")
        // Return false to not call onRebind when a client re-binds
        return false
    }

    // Method to check if service is properly initialized and running
    fun isServiceReady(): Boolean {
        return this::notificationManager.isInitialized &&
               this::aiManager.isInitialized &&
               this::conversationHistoryManager.isInitialized &&
               this::interactionStateManager.isInitialized &&
               this::tokenBucket.isInitialized
    }

    @VisibleForTesting
    internal fun buildStatsNotificationForTests(): android.app.Notification {
        return createStatsNotification()
    }

    @VisibleForTesting
    internal fun markBackendAuthExpiredForTests(expired: Boolean) {
        authExpired = expired
    }
}
