package com.example.timelinter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.*

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.*
import android.app.usage.UsageStats
import android.app.PendingIntent
import androidx.core.app.Person
import com.example.timelinter.BrowserUrlAccessibilityService
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_HOME

class AppUsageMonitorService : Service() {
    private val TAG = "AppUsageMonitorService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var notificationManager: NotificationManager
    private lateinit var aiManager: AIInteractionManager
    private lateinit var conversationHistoryManager: ConversationHistoryManager
    private lateinit var interactionStateManager: InteractionStateManager

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
    }

    // Timings
    private val statsUpdateIntervalSeconds = 30L // How often to update the stats notification
    private val checkIntervalSeconds = 10L // How often to check app usage for time tracking

    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val dailyStartTime = AtomicLong(getStartOfDay())
    private val sessionWastedTime = AtomicLong(0)
    private val dailyWastedTime = AtomicLong(0)
    private var currentApp: String? = null
    private var lastAppChangeTime = AtomicLong(System.currentTimeMillis())

    // Token bucket threshold tracking
    private val bucketRemainingMs = AtomicLong(0L)
    private val bucketAccumulatedNonWastefulMs = AtomicLong(0L) // New state for accumulator
    private val lastBucketUpdateTime = AtomicLong(System.currentTimeMillis())

    // Coroutine scope for background tasks like API calls
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Monitoring state
    @Volatile private var isMonitoringScheduled = false
    private var mainLoopFuture: ScheduledFuture<*>? = null
    private var statsFuture: ScheduledFuture<*>? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    // Persons for MessagingStyle
    private lateinit var userPerson: Person
    private lateinit var aiPerson: Person

    // State tracking
    @Volatile private var wasPreviouslyWasteful = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
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
            userInteractionTemplate = readRawResource(R.raw.gemini_user_template)
        )
        
        // Then initialize AIInteractionManager with the conversationHistoryManager
        aiManager = AIInteractionManager(this, conversationHistoryManager)

        // Initialize Persons
        userPerson = Person.Builder().setName("You").setKey("user").build()
        val coachName = ApiKeyManager.getCoachName(this)
        aiPerson = Person.Builder().setName(coachName).setKey("ai").setBot(true).build()

        // Initialize token bucket with maximum threshold
        val maxThresholdMs = TimeUnit.MINUTES.toMillis(
            SettingsManager.getMaxThresholdMinutes(this).toLong()
        )
        val persistedRemaining = SettingsManager.getThresholdRemainingMs(this, maxThresholdMs)
        val initialRemaining = if (persistedRemaining <= 0L) {
            Log.i(TAG, "Persisted bucket was empty or missing. Resetting to max: ${maxThresholdMs} ms")
            SettingsManager.setThresholdRemainingMs(this, maxThresholdMs)
            maxThresholdMs
        } else {
            minOf(persistedRemaining, maxThresholdMs)
        }
        bucketRemainingMs.set(initialRemaining)
        
        // Initialize accumulated non-wasteful time
        val persistedAccumulated = SettingsManager.getAccumulatedNonWastefulMs(this, 0L)
        bucketAccumulatedNonWastefulMs.set(persistedAccumulated)
        
        lastBucketUpdateTime.set(System.currentTimeMillis())

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
        if (isMonitoringScheduled) {
             Log.d(TAG, "startMonitoring: Monitoring already scheduled. Skipping.")
             return
        }
        
        Log.d(TAG, "startMonitoring: Scheduling tasks. Check: $checkIntervalSeconds s, Stats: $statsUpdateIntervalSeconds s")
        try {
            // Schedule the main interaction loop
            mainLoopFuture = executor.scheduleAtFixedRate({
                try {
                    Log.v(TAG, "Scheduled task running: mainInteractionLoop")
                    mainInteractionLoop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error within mainInteractionLoop task", t)
                }
            }, 0, checkIntervalSeconds, TimeUnit.SECONDS)

            // Schedule stats notification update less frequently
             statsFuture = executor.scheduleAtFixedRate({
                 try {
                    Log.d(TAG, "Scheduled task running: updateStatsNotification")
                    updateStatsNotification()
                 } catch (t: Throwable) {
                    Log.e(TAG, "Error within updateStatsNotification task", t)
                 }
             }, 5, statsUpdateIntervalSeconds, TimeUnit.SECONDS)
             
             isMonitoringScheduled = true
             Log.i(TAG, "Monitoring tasks scheduled successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling monitoring tasks", e)
             isMonitoringScheduled = false
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
            isMonitoringScheduled = false
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
                        Log.i(TAG, "Screen turned off; stopping monitoring tasks")
                        if (isMonitoringScheduled) stopMonitoringTasks("screen off")
                    }
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_UNLOCKED -> {
                        // Only resume if truly unlocked and interactive
                        if (!isDeviceLockedOrScreenOff()) {
                            Log.i(TAG, "User present/screen on; attempting to start monitoring")
                            startMonitoring()
                        } else {
                            Log.v(TAG, "Received ${action} but device still locked or screen off; not starting")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addAction(Intent.ACTION_USER_UNLOCKED)
            }
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
        
        val currentState = interactionStateManager.getCurrentState()
        Log.v(TAG, "Main loop: State=$currentState, App=${effectiveInfo?.packageName}, Wasteful=${effectiveInfo?.isWasteful}")
        
        when (currentState) {
            InteractionState.OBSERVING -> {
                handleObservingState(effectiveInfo)
            }
            InteractionState.CONVERSATION_ACTIVE -> {
                handleConversationActiveState(effectiveInfo)
            }
            InteractionState.WAITING_FOR_RESPONSE -> {
                handleWaitingForResponseState(effectiveInfo)
            }
        }
    }

    private fun handleObservingState(appInfo: AppInfo?) {
        // Step 0-1 from interaction.md
        
        val isWasteful = appInfo?.isWasteful == true
        val isAllowed = if (appInfo != null) interactionStateManager.isAllowed(appInfo.readableName) else false
        val remaining = bucketRemainingMs.get()
        if (!TriggerDecider.shouldTrigger(isWasteful, isAllowed, remaining)) {
            return
        }

        // Threshold exceeded - start conversation
        Log.i(TAG, "Threshold exceeded for ${appInfo?.readableName ?: "Unknown"}, starting conversation (bucket empty)")
        
        // Step 1b: Create initial AI history and start conversation
        conversationHistoryManager.startNewSession(
            appName = appInfo?.readableName ?: "Unknown App",
            sessionTimeMs = sessionWastedTime.get(),
            dailyTimeMs = dailyWastedTime.get()
        )
        
        interactionStateManager.startConversation()
        
        // Step 2: Send to AI and get initial response
        generateAIResponse(appInfo)
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
                sessionTimeMs = sessionWastedTime.get(),
                dailyTimeMs = dailyWastedTime.get()
            )
            
            // Continue conversation
            interactionStateManager.continueConversation()
            
            // Step 7: Go to step 2 - send to AI again
            generateAIResponse(appInfo)
        }
    }

    private fun generateAIResponse(appInfo: AppInfo?) {
        val currentModel = aiManager.getInitializedModel()
        if (currentModel == null) {
            Log.e(TAG, "Cannot generate AI response: Model not initialized")
            return
        }

        serviceScope.launch {
            try {
                val apiContents = conversationHistoryManager.getHistoryForAPI()
                Log.d(TAG, "Sending to AI. History size: ${apiContents.size}")

                val response = currentModel.generateContent(*apiContents.toTypedArray())
                // Function-calling only: parse structured calls and model text
                val parsedResponse = GeminiFunctionCallParser.parse(response)
                // Process tools
                var shouldResetConversation = false
                for (tool in parsedResponse.tools) {
                    when (tool) {
                        is ToolCommand.Allow -> {
                            Log.i(TAG, "Processing ALLOW tool: ${tool.minutes} minutes${tool.app?.let { " for $it" } ?: ""}")
                            interactionStateManager.applyAllowCommand(tool)
                            // Log tool usage to API history only
                            conversationHistoryManager.addToolLog(tool)
                            shouldResetConversation = true
                        }
                        is ToolCommand.Remember -> {
                            Log.i(TAG, "Processing REMEMBER tool: ${tool.content}")
                            val tmp: Int? = tool.durationMinutes
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

                // Step 4: If there was any non-tool answer, display to user
                if (parsedResponse.userMessage.isNotEmpty()) {
                    conversationHistoryManager.addAIMessage(parsedResponse.userMessage)
                    showConversationNotification(
                        appName = appInfo?.readableName ?: "Unknown App",
                        sessionTimeMs = sessionWastedTime.get(),
                        dailyTimeMs = dailyWastedTime.get()
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
                val sessionMs = intent.getLongExtra(EXTRA_SESSION_TIME_MS, 0L)
                val dailyMs = intent.getLongExtra(EXTRA_DAILY_TIME_MS, 0L)

                // Step 6b: User answered, save response and continue
                conversationHistoryManager.addUserMessage(replyText, appName, sessionMs, dailyMs)
                interactionStateManager.continueConversation()
                
                // Step 7: Go to step 2 - send to AI
                val currentAppInfo = getCurrentAppUsage()
                generateAIResponse(currentAppInfo)

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
        val isWasteful: Boolean
    )

    private fun getAppInfoFromCurrentApp(): AppInfo? {
        val pkg = currentApp ?: return null
        return if (pkg.startsWith("web:")) {
            val host = pkg.removePrefix("web:")
            val wasteful = TimeWasterAppManager.isTimeWasterSite(applicationContext, host)
            AppInfo(packageName = pkg, readableName = host, isWasteful = wasteful)
        } else {
            val readable = getReadableAppName(pkg)
            val wasteful = isWastefulApp(pkg)
            AppInfo(packageName = pkg, readableName = readable, isWasteful = wasteful)
        }
    }

    private fun getCurrentAppUsage(): AppInfo? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        // Look back further to get more reliable events
        val beginTime = endTime - 1000 * 60 * 2 // 2 minutes

        try {
            // Check if user is unlocked (required for Android R+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = getSystemService(USER_SERVICE) as UserManager
                if (!userManager.isUserUnlocked) {
                    Log.w(TAG, "User is locked, cannot query usage events")
                    return null
                }
            }

            // If device is locked or screen is not interactive, treat as no foreground app
            if (isDeviceLockedOrScreenOff()) {
                Log.v(TAG, "Device locked or screen off; returning no current app")
                return null
            }

            // Use queryEvents instead of queryUsageStats for real-time detection
            val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
            if (usageEvents == null) {
                Log.w(TAG, "queryEvents returned null")
                return null
            }

            var currentForegroundApp: String? = null
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
                } else if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED) {
                    // If the same app that was in foreground gets paused, it's no longer foreground
                    if (event.packageName == currentForegroundApp) {
                        currentForegroundApp = null
                    }
                }
            }
            
            // If we found a foreground app, return its info unless it's the launcher/home
            if (currentForegroundApp != null) {
                if (isHomeLauncher(currentForegroundApp)) {
                    Log.v(TAG, "Foreground is launcher; treating as no app")
                    return null
                }
                // Check if it is a browser and whether we have a hostname from the accessibility service
                val browserHost = BrowserUrlAccessibilityService.getCurrentHostname()
                if (browserHost != null && isBrowserPackage(currentForegroundApp)) {
                    val wasteful = TimeWasterAppManager.isTimeWasterSite(applicationContext, browserHost)
                    return AppInfo(
                        packageName = "web:$browserHost",
                        readableName = browserHost,
                        isWasteful = wasteful
                    )
                }

                val readableName = getReadableAppName(currentForegroundApp)
                val isWasteful = isWastefulApp(currentForegroundApp)
                
                return AppInfo(
                    packageName = currentForegroundApp,
                    readableName = readableName,
                    isWasteful = isWasteful
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
        // Only use newly detected app; do not carry previous when detection is null
        val detectedApp = appInfo?.packageName ?: ""
        val isCurrentlyWasteful = appInfo?.isWasteful == true

        // ---------------- Token bucket update ----------------
        val now = System.currentTimeMillis()
        val previousUpdate = lastBucketUpdateTime.getAndSet(now) // Important: getAndSet ensures atomicity for lastBucketUpdateTime
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(SettingsManager.getMaxThresholdMinutes(this).toLong()),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(SettingsManager.getReplenishIntervalMinutes(this).toLong()),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(SettingsManager.getReplenishAmountMinutes(this).toLong())
        )
        
        val updateResult = TokenBucket.update(
            previousRemainingMs = bucketRemainingMs.get(),
            previousAccumulatedNonWastefulMs = bucketAccumulatedNonWastefulMs.get(), // Pass accumulator
            lastUpdateTimeMs = previousUpdate, // Use the value from *before* getAndSet
            nowMs = now,
            isCurrentlyWasteful = isCurrentlyWasteful,
            config = config
        )

        // Update state from result
        val oldRemaining = bucketRemainingMs.get()
        if (updateResult.newRemainingMs != oldRemaining) {
            bucketRemainingMs.set(updateResult.newRemainingMs)
        }
        if (updateResult.newAccumulatedNonWastefulMs != bucketAccumulatedNonWastefulMs.get()) {
            bucketAccumulatedNonWastefulMs.set(updateResult.newAccumulatedNonWastefulMs)
        }
        
        // Persist values occasionally (every minute)
        // Check now - previousUpdate (delta) to ensure we're not saving too aggressively if this is called more often.
        if ((now - previousUpdate) >= TimeUnit.MINUTES.toMillis(1)) {
            SettingsManager.setThresholdRemainingMs(this, bucketRemainingMs.get())
            SettingsManager.setAccumulatedNonWastefulMs(this, bucketAccumulatedNonWastefulMs.get()) // Persist accumulator
        }

        // If bucket just transitioned from empty (<=0) to full (== max), archive & clear
        val maxThresholdMs = config.maxThresholdMs
        val becameFull = oldRemaining <= 0L && updateResult.newRemainingMs >= maxThresholdMs
        if (becameFull && isCurrentlyWasteful) {
            Log.i(TAG, "Token bucket refilled to full while wasteful: archiving and clearing conversation.")
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
             
             // Add time spent on previous app IF IT WAS WASTEFUL
             currentApp?.let { app ->
                 if (isWastefulApp(app)) {
                     val timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
                     if (timeSpent < TimeUnit.MINUTES.toMillis(5)) { 
                         sessionWastedTime.addAndGet(timeSpent)
                         dailyWastedTime.addAndGet(timeSpent)
                         Log.d(TAG, "Added $timeSpent ms to wasteful time (previous app: $app)")
                     } else {
                          Log.w(TAG, "Ignoring large time difference ($timeSpent ms) for previous app: $app")
                     }
                 }
             }

            // Update currentApp; if detection is empty, clear current app
            currentApp = if (detectedApp.isNotEmpty()) detectedApp else null
             lastAppChangeTime.set(System.currentTimeMillis())

             // Reset session timer ONLY when a *new* wasteful app starts
             if (isWastefulApp(detectedApp)) {
                  sessionWastedTime.set(0)
                  sessionStartTime.set(System.currentTimeMillis())
                  Log.d(TAG, "New wasteful app detected: $detectedApp. Reset session timer.")
             }
         } else if (isCurrentlyWasteful) {
             // Accumulate time for current wasteful app
             val timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
              if (timeSpent < TimeUnit.MINUTES.toMillis(5)) {
                 sessionWastedTime.addAndGet(timeSpent)
                 dailyWastedTime.addAndGet(timeSpent)
                 Log.v(TAG, "Accumulated $timeSpent ms for current wasteful app: $currentApp")
              }
             lastAppChangeTime.set(System.currentTimeMillis())
         }
         
        wasPreviouslyWasteful = isCurrentlyWasteful
    }

    private fun tryArchiveMemoriesThenClear(appInfo: AppInfo?) {
        val currentModel = aiManager.getInitializedModel()
        if (currentModel == null) {
            Log.w(TAG, "Model not initialized; clearing histories without archive prompt")
            conversationHistoryManager.clearHistories()
            return
        }

        val appName = appInfo?.readableName ?: "Unknown App"
        // Add API-only user message asking for memory suggestions
        val prompt = "This conversation is to be archived. Is there anything worth remembering that wasn't already in the AI memory at the beginning of this conversation? Respond with concise bullet points or 'No new memory'."
        conversationHistoryManager.addApiOnlyUserMessage(
            messageText = prompt,
            currentAppName = appName,
            sessionTimeMs = sessionWastedTime.get(),
            dailyTimeMs = dailyWastedTime.get()
        )

        // Build contents for a one-off generation using the updated API history
        val contents = conversationHistoryManager.getHistoryForAPI()
        aiManager.generateFromContents(contents) { responseText ->
            if (!responseText.isNullOrBlank()) {
                val trimmed = responseText.trim()
                if (!trimmed.equals("No new memory", ignoreCase = true)) {
                    AIMemoryManager.addPermanentMemory(this, trimmed)
                    // Update memory shown in log screen
                    ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(this))
                }
            }
            // Finally clear histories for a new session
            conversationHistoryManager.clearHistories()
        }
    }

    private fun isWastefulApp(packageName: String): Boolean {
        return if (packageName.startsWith("web:")) {
            val host = packageName.removePrefix("web:")
            TimeWasterAppManager.isTimeWasterSite(applicationContext, host)
        } else {
            TimeWasterAppManager.isTimeWasterApp(applicationContext, packageName)
        }
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        return pkg in setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx"
        )
    }

    private fun showConversationNotification(appName: String = "Unknown App", sessionTimeMs: Long = 0L, dailyTimeMs: Long = 0L) {
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
            putExtra(EXTRA_SESSION_TIME_MS, sessionTimeMs)
            putExtra(EXTRA_DAILY_TIME_MS, dailyTimeMs)
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
        val currentAppName = currentApp?.let { app ->
            if (isWastefulApp(app)) getReadableAppName(app) else "No distracting app active"
        } ?: "No distracting app active"
        val maxThresholdMs = TimeUnit.MINUTES.toMillis(
            SettingsManager.getMaxThresholdMinutes(this).toLong()
        )
        val bucketRemaining = bucketRemainingMs.get()
        val bucketText = if (bucketRemaining < maxThresholdMs) {
            "Bucket: ${formatDuration(bucketRemaining)} remaining"
        } else {
            "Bucket: full"
        }
        
        // Calculate current app time when on a wasteful app
        val currentAppTime = currentApp?.let { app ->
            if (isWastefulApp(app)) {
                val timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
                if (timeSpent < TimeUnit.MINUTES.toMillis(5)) {
                    "Current App Time: ${formatDuration(timeSpent)}"
                } else null
            } else null
        }
        
        Log.d(TAG, "Creating stats notification. App: $currentAppName, Session: ${sessionWastedTime.get()}ms, Daily: ${dailyWastedTime.get()}ms, Bucket: ${bucketRemaining}ms")
        
        // Build notification text with current app time when bucket is full
        val notificationText = buildString {
            appendLine(monitoringStatus)
            appendLine("Current: $currentAppName")
            if (currentAppTime != null && bucketRemaining >= maxThresholdMs) {
                appendLine(currentAppTime)
            }
            appendLine("Session Time: ${formatDuration(sessionWastedTime.get())}")
            appendLine("Daily Time: ${formatDuration(dailyWastedTime.get())}")
            appendLine(bucketText)
        }
        
        val builder = NotificationCompat.Builder(this, STATS_CHANNEL_ID)
            .setContentTitle("Time Linter")
            .setContentText(monitoringStatus)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(notificationText)
                 .setSummaryText("Time Linter Stats")
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN) 
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null) 
            .setVibrate(longArrayOf(0L))
            
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
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val locked = keyguard.isKeyguardLocked
            val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) power.isInteractive else true
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

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "$hours h ${minutes % 60} min"
            minutes > 0 -> "$minutes min ${seconds % 60} s"
            else -> "$seconds s"
        }
    }

    private fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
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
        // Persist bucket value so it survives service restarts
        SettingsManager.setThresholdRemainingMs(this, bucketRemainingMs.get())
        SettingsManager.setAccumulatedNonWastefulMs(this, bucketAccumulatedNonWastefulMs.get()) // Persist accumulator
        serviceScope.cancel()
        executor.shutdown()
        isMonitoringScheduled = false
        
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

    override fun onBind(intent: Intent?): IBinder? = null
}
