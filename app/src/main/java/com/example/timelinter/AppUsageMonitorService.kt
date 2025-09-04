package com.example.timelinter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.google.ai.client.generativeai.type.Content
import kotlinx.coroutines.*

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.*
import android.app.usage.UsageStats
import android.app.PendingIntent
import androidx.core.app.Person
import com.example.timelinter.BrowserUrlAccessibilityService

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
    private val bucketRemainingMs = AtomicLong(0)
    private val lastBucketUpdateTime = AtomicLong(System.currentTimeMillis())

    // Coroutine scope for background tasks like API calls
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Monitoring state
    @Volatile private var isMonitoringScheduled = false

    // Persons for MessagingStyle
    private lateinit var userPerson: Person
    private lateinit var aiPerson: Person

    // State tracking
    @Volatile private var wasPreviouslyWasteful = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        bucketRemainingMs.set(minOf(persistedRemaining, maxThresholdMs))
        lastBucketUpdateTime.set(System.currentTimeMillis())

        Log.d(TAG, "Starting foreground service with notification ID: $STATS_NOTIFICATION_ID")
        try {
            startForeground(STATS_NOTIFICATION_ID, createStatsNotification())
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startForeground", e)
        }
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
            executor.scheduleAtFixedRate({
                try {
                    Log.v(TAG, "Scheduled task running: mainInteractionLoop")
                    mainInteractionLoop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error within mainInteractionLoop task", t)
                }
            }, 0, checkIntervalSeconds, TimeUnit.SECONDS)

            // Schedule stats notification update less frequently
             executor.scheduleAtFixedRate({
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

    /**
     * Main interaction loop implementing the flow from interaction.md
     */
    private fun mainInteractionLoop() {
        // Clean up expired allows
        interactionStateManager.cleanupExpiredAllows()
        
        // Get current app usage
        val currentAppInfo = getCurrentAppUsage()
        updateTimeTracking(currentAppInfo)
        
        val currentState = interactionStateManager.getCurrentState()
        Log.v(TAG, "Main loop: State=$currentState, App=${currentAppInfo?.packageName}, Wasteful=${currentAppInfo?.isWasteful}")
        
        when (currentState) {
            InteractionState.OBSERVING -> {
                handleObservingState(currentAppInfo)
            }
            InteractionState.CONVERSATION_ACTIVE -> {
                handleConversationActiveState(currentAppInfo)
            }
            InteractionState.WAITING_FOR_RESPONSE -> {
                handleWaitingForResponseState(currentAppInfo)
            }
        }
    }

    private fun handleObservingState(appInfo: AppInfo?) {
        // Step 0-1 from interaction.md
        
        if (appInfo?.isWasteful != true) {
            // Not on wasteful app, continue observing
            return
        }
        
        // Check if this app is allowed
        if (interactionStateManager.isAllowed(appInfo.readableName)) {
            Log.d(TAG, "App '${appInfo.readableName}' is currently allowed, skipping")
            return
        }

        // Per interaction.md, trigger ONLY based on threshold being exceeded
        if (bucketRemainingMs.get() > 0) {
            Log.v(TAG, "Threshold not yet exceeded (remaining ${bucketRemainingMs.get()} ms)")
            return
        }

        // Threshold exceeded - start conversation
        Log.i(TAG, "Threshold exceeded for ${appInfo.readableName}, starting conversation (bucket empty)")
        
        // Step 1b: Create initial AI history and start conversation
        conversationHistoryManager.startNewSession(
            appName = appInfo.readableName,
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
            conversationHistoryManager.clearHistories()
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
            conversationHistoryManager.clearHistories()
            return
        }
        
        // From this point we know appInfo is non-null and wasteful
        val nonNullAppInfo = appInfo!!

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
                val rawResponse = response.text
                
                if (rawResponse != null) {
                    Log.d(TAG, "Raw AI Response: $rawResponse")
                    
                    // Step 3: Parse response and run tools
                    val parsedResponse = ToolParser.parseAIResponse(rawResponse)
                    
                    // Process tools
                    var shouldResetConversation = false
                    for (tool in parsedResponse.tools) {
                        when (tool) {
                            is ToolCommand.Allow -> {
                                Log.i(TAG, "Processing ALLOW tool: ${tool.minutes} minutes${tool.app?.let { " for $it" } ?: ""}")
                                interactionStateManager.applyAllowCommand(tool)
                                shouldResetConversation = true
                            }
                            is ToolCommand.Remember -> {
                                Log.i(TAG, "Processing REMEMBER tool: ${tool.content}")
                                if (tool.durationMinutes != null) {
                                    AIMemoryManager.addTemporaryMemory(this@AppUsageMonitorService, tool.content, tool.durationMinutes)
                                } else {
                                    AIMemoryManager.addPermanentMemory(this@AppUsageMonitorService, tool.content)
                                }
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
                        // Step 5a: ALLOW tool used or no non-tool message -> reset
                        Log.d(TAG, "Resetting conversation (ALLOW used or no message)")
                        interactionStateManager.resetToObserving()
                        conversationHistoryManager.clearHistories()
                    } else {
                        // Step 5b: Continue conversation, wait for response
                        Log.d(TAG, "Continuing conversation, waiting for user response")
                        interactionStateManager.startWaitingForResponse()
                    }
                    
                } else {
                    Log.w(TAG, "AI response was null")
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

    private fun getCurrentAppUsage(): AppInfo? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        // Look back further to get more reliable events
        val beginTime = endTime - 1000 * 60 * 2 // 2 minutes

        try {
            // Check if user is unlocked (required for Android R+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = getSystemService(Context.USER_SERVICE) as android.os.UserManager
                if (!userManager.isUserUnlocked) {
                    Log.w(TAG, "User is locked, cannot query usage events")
                    return null
                }
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
            
            // If we found a foreground app, return its info
            if (currentForegroundApp != null) {
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
        val detectedApp = appInfo?.packageName ?: ""
        val isCurrentlyWasteful = appInfo?.isWasteful == true
        
        // Clear history on transition from wasteful to non-wasteful
        if (wasPreviouslyWasteful && !isCurrentlyWasteful) {
            Log.i(TAG, "Transition from wasteful to non-wasteful app detected. Clearing conversation history.")
            conversationHistoryManager.clearHistories()
            interactionStateManager.resetToObserving()
        }

        // ---------------- Token bucket update ----------------
        val now = System.currentTimeMillis()
        val delta = now - lastBucketUpdateTime.get()
        lastBucketUpdateTime.set(now)

        val maxThresholdMs = TimeUnit.MINUTES.toMillis(
            SettingsManager.getMaxThresholdMinutes(this).toLong()
        )
        val replenishIntervalMs = TimeUnit.MINUTES.toMillis(
            SettingsManager.getReplenishIntervalMinutes(this).toLong()
        )
        val replenishAmountMs = TimeUnit.MINUTES.toMillis(
            SettingsManager.getReplenishAmountMinutes(this).toLong()
        )

        if (delta > 0) {
            if (isCurrentlyWasteful) {
                // Consume tokens
                bucketRemainingMs.addAndGet(-delta)
            } else {
                // Replenish tokens proportionally to the elapsed time
                if (replenishIntervalMs > 0) {
                    val tokensToAdd = (delta / replenishIntervalMs) * replenishAmountMs
                    if (tokensToAdd > 0) {
                        bucketRemainingMs.addAndGet(tokensToAdd)
                    }
                }
            }

            // Clamp to [0, max]
            if (bucketRemainingMs.get() > maxThresholdMs) {
                bucketRemainingMs.set(maxThresholdMs)
            } else if (bucketRemainingMs.get() < 0) {
                bucketRemainingMs.set(0)
            }

            // Persist value occasionally (every minute)
            if (delta >= TimeUnit.MINUTES.toMillis(1)) {
                SettingsManager.setThresholdRemainingMs(this, bucketRemainingMs.get())
            }
        }

        // ---------------- Existing time tracking logic ----------------

        // App change detection and time accumulation logic
        if (detectedApp != currentApp) {
             Log.d(TAG, "App changed from '$currentApp' to '$detectedApp'")
             
             // Add time spent on previous app IF IT WAS WASTEFUL
             if (currentApp != null && isWastefulApp(currentApp!!)) {
                 val timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
                 if (timeSpent < TimeUnit.MINUTES.toMillis(5)) { 
                     sessionWastedTime.addAndGet(timeSpent)
                     dailyWastedTime.addAndGet(timeSpent)
                     Log.d(TAG, "Added $timeSpent ms to wasteful time (previous app: $currentApp)")
                 } else {
                      Log.w(TAG, "Ignoring large time difference ($timeSpent ms) for previous app: $currentApp")
                 }
             }

             currentApp = detectedApp
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
        val currentAppName = if (currentApp != null && isWastefulApp(currentApp!!)) {
            getReadableAppName(currentApp!!)
        } else {
            "No distracting app active"
        }
        
        Log.d(TAG, "Creating stats notification. App: $currentAppName, Session: ${sessionWastedTime.get()}ms, Daily: ${dailyWastedTime.get()}ms")
        val builder = NotificationCompat.Builder(this, STATS_CHANNEL_ID)
            .setContentTitle("Time Linter")
            .setContentText(monitoringStatus)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("""
                    $monitoringStatus
                    Current: $currentAppName
                    Session Time: ${formatDuration(sessionWastedTime.get())}
                    Daily Time: ${formatDuration(dailyWastedTime.get())}
                """.trimIndent())
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        serviceScope.cancel()
        executor.shutdown()
        isMonitoringScheduled = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 