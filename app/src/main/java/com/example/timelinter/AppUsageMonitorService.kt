package com.example.timelinter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
// Gemini SDK Imports
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.asTextOrNull
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.*

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.*
import android.app.usage.UsageStats
import android.app.PendingIntent
import androidx.core.app.Person
import java.io.BufferedReader
import java.io.InputStreamReader

// Simple data class for messages
data class ChatMessage(val text: CharSequence, val timestamp: Long, val sender: Person, val isUser: Boolean)

class AppUsageMonitorService : Service() {
    private val TAG = "AppUsageMonitorService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var notificationManager: NotificationManager

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
    private val naggingIntervalSeconds = 30L // Minimum interval between nagging notifications
    private val checkIntervalSeconds = 10L // How often to check app usage for time tracking

    private val lastNaggingNotificationTime = AtomicLong(0) // Track time for nagging notification specifically
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val dailyStartTime = AtomicLong(getStartOfDay())
    private val sessionWastedTime = AtomicLong(0)
    private val dailyWastedTime = AtomicLong(0)
    private var currentApp: String? = null
    private var lastAppChangeTime = AtomicLong(System.currentTimeMillis())

    // Coroutine scope for background tasks like API calls
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Gemini Model
    private var generativeModel: GenerativeModel? = null

    // Monitoring state
    @Volatile private var isMonitoringScheduled = false

    // Persons for MessagingStyle
    private lateinit var userPerson: Person
    private lateinit var aiPerson: Person

    // Conversation history
    private val conversationHistory = mutableListOf<ChatMessage>()

    // State tracking
    @Volatile private var wasPreviouslyWasteful = false
    private val naggingPausedUntil = AtomicLong(0)

    // --- Prompt Loading --- 
    private var systemPrompt: String? = null
    private var initialPromptTemplate: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        loadPrompts() // Load prompts early
        initializeGeminiModel() // Initialize model (will use loaded system prompt if available)

        // Initialize Persons
        userPerson = Person.Builder().setName("You").setKey("user").build()
        aiPerson = Person.Builder().setName("Time Coach").setKey("ai").setBot(true).build()

        Log.d(TAG, "Starting foreground service with notification ID: $STATS_NOTIFICATION_ID")
        try {
            startForeground(STATS_NOTIFICATION_ID, createStatsNotification())
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startForeground", e)
        }
    }

    private fun loadPrompts() {
        systemPrompt = loadRawResource(R.raw.gemini_system_prompt)
        initialPromptTemplate = loadRawResource(R.raw.gemini_initial_prompt_template)
        if (systemPrompt == null) {
            Log.e(TAG, "Failed to load system prompt!")
        }
        if (initialPromptTemplate == null) {
            Log.e(TAG, "Failed to load initial prompt template!")
        }
    }

    private fun loadRawResource(resId: Int): String? {
        return try {
            resources.openRawResource(resId).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading raw resource: $resId", e)
            null
        }
    }

    private fun initializeGeminiModel() {
        val apiKey = ApiKeyManager.getKey(this)
        if (systemPrompt == null) {
             Log.w(TAG, "System prompt not loaded yet, cannot initialize model.")
             return // Or retry loading?
        }
        if (!apiKey.isNullOrEmpty()) {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-2.0-flash-lite",
                    apiKey = apiKey,
                    // Pass the loaded system instruction here
                    systemInstruction = content(role="system") { text(systemPrompt!!) }
                )
                Log.i(TAG, "GenerativeModel initialized successfully with system prompt.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GenerativeModel", e)
                generativeModel = null
            }
        } else {
            Log.w(TAG, "Cannot initialize GenerativeModel: API Key not found.")
            generativeModel = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        // Ensure model is initialized if API key was added later
        if (generativeModel == null) { 
             loadPrompts() // Reload prompts in case they failed initially
             initializeGeminiModel() 
        }
        when (intent?.action) {
            ACTION_HANDLE_REPLY -> {
                handleReply(intent)
                lastNaggingNotificationTime.set(0) // Allow immediate response display
            }
            else -> {
                Log.d(TAG, "onStartCommand: Default action - ensuring monitoring is started.")
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        // Prevent rescheduling if already running
        if (isMonitoringScheduled) {
             Log.d(TAG, "startMonitoring: Monitoring already scheduled. Skipping.")
             return
        }
        
        // Check if executor is shut down (e.g., after onDestroy -> onCreate)
        // Recreate if necessary
        // NOTE: This check might need refinement depending on service lifecycle edge cases.
        // if (executor.isShutdown || executor.isTerminated) {
        //     Log.w(TAG, "Executor was shutdown. Recreating.")
        //     executor = Executors.newSingleThreadScheduledExecutor()
        // }

        Log.d(TAG, "startMonitoring: Scheduling tasks. Check: $checkIntervalSeconds s, Stats: $statsUpdateIntervalSeconds s")
        try {
            // Schedule the main check frequently
            executor.scheduleAtFixedRate({
                // Use try-catch within scheduled tasks to prevent stopping the schedule
                try {
                    Log.v(TAG, "Scheduled task running: checkAppUsage")
                    checkAppUsage()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error within checkAppUsage task", t)
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
             
             isMonitoringScheduled = true // Set flag after successful scheduling
             Log.i(TAG, "Monitoring tasks scheduled successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling monitoring tasks", e)
             isMonitoringScheduled = false // Ensure flag is false if scheduling fails
        }
    }

    private fun checkAppUsage() {
        Log.d(TAG, "checkAppUsage started")
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * checkIntervalSeconds

        var foregroundApp: String? = null
        try {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            if (stats != null && stats.isNotEmpty()) {
                var recentStat: UsageStats? = null
                for (usageStats in stats) {
                    // *** Filter out self ***
                    if (usageStats.packageName == applicationContext.packageName) {
                        continue 
                    }
                    if (usageStats.packageName == ""){
                        continue
                    }
                    if (recentStat == null || usageStats.lastTimeUsed > recentStat.lastTimeUsed) {
                        recentStat = usageStats
                    }
                }
                foregroundApp = recentStat?.packageName
            }
            Log.d(TAG, "Foreground app detected (excluding self): $foregroundApp (Current: $currentApp)")

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error querying usage stats", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage stats", e)
            return
        }

        val detectedApp = foregroundApp ?: ""
        val isCurrentlyWasteful = isWastefulApp(detectedApp)
        Log.d(TAG, "App Check: Detected='$detectedApp', Current='$currentApp', IsWasteful=$isCurrentlyWasteful, WasWasteful=$wasPreviouslyWasteful")

        // *** Check if nagging is paused ***
        val now = System.currentTimeMillis()
        if (now < naggingPausedUntil.get()) {
            Log.v(TAG, "Nagging is paused until ${Date(naggingPausedUntil.get())}. Skipping nag check.")
            wasPreviouslyWasteful = isCurrentlyWasteful
            Log.d(TAG, "checkAppUsage finished (Nagging Paused). Session: ${sessionWastedTime.get()}ms, Daily: ${dailyWastedTime.get()}ms")
            return
        }

        // *** Clear history on transition from wasteful to non-wasteful ***
        if (wasPreviouslyWasteful && !isCurrentlyWasteful) {
            Log.i(TAG, "Transition from wasteful to non-wasteful app detected. Clearing conversation history.")
            conversationHistory.clear()
        }

        // --- App change detection and time accumulation logic ---
        var timeSpent = 0L
        if (detectedApp != currentApp) {
             Log.d(TAG, "App changed from '$currentApp' to '$detectedApp'")
             // Add time spent on previous app IF IT WAS WASTEFUL
             if (currentApp != null && isWastefulApp(currentApp!!)) {
                 timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
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
             } else {
                  // No need to reset sessionWastedTime if the new app isn't wasteful,
                  // as it wasn't accumulating for the previous non-wasteful one anyway.
                  Log.d(TAG, "New non-wasteful app detected: $detectedApp.")
             }
         } else if (currentApp != null && isWastefulApp(currentApp!!)) {
             // Accumulate time for current wasteful app
             timeSpent = System.currentTimeMillis() - lastAppChangeTime.get()
              if (timeSpent < TimeUnit.MINUTES.toMillis(5)) {
                 sessionWastedTime.addAndGet(timeSpent)
                 dailyWastedTime.addAndGet(timeSpent)
                 Log.d(TAG, "Accumulated $timeSpent ms for current wasteful app: $currentApp")
              } else {
                  Log.w(TAG, "Ignoring large time difference ($timeSpent ms) for current app: $currentApp")
              }
             lastAppChangeTime.set(System.currentTimeMillis())
         } else {
              Log.d(TAG, "Current app '$currentApp' ('$detectedApp') is not wasteful or hasn't changed.")
         }
         // --- End of time accumulation logic ---

        // Send nagging notification check
        if (isCurrentlyWasteful) {
            val timeSinceLastNag = now - lastNaggingNotificationTime.get()

            if (timeSinceLastNag >= naggingIntervalSeconds * 1000) {
                Log.d(TAG, "Nagging interval passed for wasteful app '$detectedApp'.")
                val currentAppName = getReadableAppName(detectedApp)
                val currentSessionMs = sessionWastedTime.get()
                val currentDailyMs = dailyWastedTime.get()

                // *** Call sendNotification, which now handles initial message generation ***
                sendNotification(currentAppName, currentSessionMs, currentDailyMs)
                lastNaggingNotificationTime.set(now)
            } else {
                Log.v(TAG, "Still within nagging interval for '$detectedApp'. Time since last: ${timeSinceLastNag}ms")
            }
        } else {
             lastNaggingNotificationTime.set(0)
        }
        
        wasPreviouslyWasteful = isCurrentlyWasteful
        Log.d(TAG, "checkAppUsage finished. Session: ${sessionWastedTime.get()}ms, Daily: ${dailyWastedTime.get()}ms")
    }

    private fun isWastefulApp(packageName: String): Boolean {
        val wastefulApps = listOf(
            "com.facebook.katana", // Facebook
            "com.instagram.android", // Instagram
            "com.twitter.android", // Twitter
            "com.google.android.youtube" // YouTube
        )
        return wastefulApps.contains(packageName)
    }

    private fun handleReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()
            if (!replyText.isNullOrEmpty()) {
                Log.i(TAG, "User replied: '$replyText'")
                // Context for potential immediate use (though history is main source)
                val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Unknown App"
                val sessionMs = intent.getLongExtra(EXTRA_SESSION_TIME_MS, 0L)
                val dailyMs = intent.getLongExtra(EXTRA_DAILY_TIME_MS, 0L)

                val userMessage = ChatMessage(replyText, System.currentTimeMillis(), userPerson, true)
                conversationHistory.add(userMessage)
                
                // Send to AI for processing the reply based on history
                sendToAI(appName, sessionMs, dailyMs) // Pass context for notification update

            } else { Log.w(TAG, "Received empty reply.") }
        } else { Log.w(TAG, "Could not extract remote input from reply intent.") }
    }

    // Helper to map conversation history for the API
    private fun mapHistoryToApiContent(): List<Content> {
        return conversationHistory.map { msg ->
            content(role = if (msg.isUser) "user" else "model") {
                text(msg.text.toString())
            }
        }
    }

    // Function for AI interaction AFTER a user reply
    private fun sendToAI(appName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        val currentModel = generativeModel ?: run {
            Log.e(TAG, "Cannot send reply to AI: GenerativeModel not initialized.")
            conversationHistory.add(ChatMessage("(Error: AI not ready for reply)", System.currentTimeMillis(), aiPerson, false))
            showConversationNotification(appName, sessionTimeMs, dailyTimeMs)
            return
        }
        if (conversationHistory.isEmpty() || !conversationHistory.last().isUser) {
             Log.w(TAG, "sendToAI called but last message is not from user. Aborting.")
             return // Should not happen if called from handleReply
        }

        val mappedHistory = mapHistoryToApiContent()
        Log.d(TAG, "Sending reply to AI. History size: ${mappedHistory.size}")

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            var pauseMinutes: Int? = null

            try {
                 // The system prompt is part of the model config now
                 // Generate response based on the full history
                 val response = currentModel.generateContent(*mappedHistory.toTypedArray())
                 val rawResponse = response.text
                 Log.d(TAG, "Raw Gemini Response (reply): $rawResponse")

                 // --- Parse response for # WAIT (same logic as before) ---
                 if (rawResponse != null) {
                    // Check for # WAIT command
                    val lines = rawResponse.lines()
                    val waitCommand = lines.find { it.startsWith("# WAIT") }
                    if (waitCommand != null) {
                         try {
                             pauseMinutes = waitCommand.removePrefix("# WAIT").trim().toIntOrNull()
                             if (pauseMinutes != null && pauseMinutes > 0) {
                                 Log.i(TAG, "AI requested WAIT for $pauseMinutes minutes.")
                                 aiResponseText = lines.firstOrNull { !it.startsWith("# WAIT") } ?: "Okay, pausing nags for $pauseMinutes minute${if (pauseMinutes > 1) "s" else ""}."
                             } else {
                                 Log.w(TAG, "Invalid minutes in WAIT command: $waitCommand")
                                 aiResponseText = rawResponse
                                 pauseMinutes = null
                             }
                         } catch (e: Exception) {
                             Log.e(TAG, "Error parsing WAIT command", e)
                             aiResponseText = rawResponse
                             pauseMinutes = null
                         }
                    } else {
                        aiResponseText = rawResponse
                    }
                 } else {
                    Log.w(TAG, "Gemini response (reply) was null.")
                    errorMessage = "(Error getting response)"
                 }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for reply", e)
                errorMessage = "(API Error: ${e.message})"
            }

            // Add AI response/error to history
            val messageToAdd = if (aiResponseText != null) {
                 Log.i(TAG, "Processed AI Response (reply): $aiResponseText")
                 ChatMessage(aiResponseText, System.currentTimeMillis(), aiPerson, false)
            } else {
                 ChatMessage(errorMessage ?: "(Unknown Error)", System.currentTimeMillis(), aiPerson, false)
            }
            // Add AI's response
            conversationHistory.add(messageToAdd)
            
            // Set pause if needed
            if (pauseMinutes != null && pauseMinutes > 0) {
                 val pauseMillis = TimeUnit.MINUTES.toMillis(pauseMinutes.toLong())
                 naggingPausedUntil.set(System.currentTimeMillis() + pauseMillis)
                 Log.i(TAG, "Nagging paused until: ${Date(naggingPausedUntil.get())}")
            }
            
            // Update notification with the new AI response
            showConversationNotification(appName, sessionTimeMs, dailyTimeMs)
        }
    }

    // Renamed and modified to handle initial prompt generation if needed
    private fun sendNotification(appName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        Log.d(TAG, "sendNotification called for $appName. History empty? ${conversationHistory.isEmpty()}")
        if (conversationHistory.isEmpty()) {
            // History is empty, generate initial AI message based on context
            val template = initialPromptTemplate
            val model = generativeModel
            if (template != null && model != null) {
                val formattedPrompt = template
                    .replace("{{APP_NAME}}", appName)
                    .replace("{{SESSION_TIME}}", formatDuration(sessionTimeMs))
                    .replace("{{DAILY_TIME}}", formatDuration(dailyTimeMs))
                
                Log.d(TAG, "Generating initial AI message with prompt:\n$formattedPrompt")
                generateInitialAIResponse(formattedPrompt, appName, sessionTimeMs, dailyTimeMs)
            } else {
                Log.e(TAG, "Cannot generate initial message: Template or Model is null.")
                // Fallback: Just show the notification empty or with a static message?
                // For now, just call show to display whatever might be there (nothing)
                 showConversationNotification(appName, sessionTimeMs, dailyTimeMs)
            }
        } else {
            // History exists, just update the notification display (no new AI call needed here)
            Log.d(TAG, "History exists, just updating notification display.")
            showConversationNotification(appName, sessionTimeMs, dailyTimeMs)
        }
    }

    // New function to generate the VERY FIRST AI response in a conversation
    private fun generateInitialAIResponse(formattedPrompt: String, appName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        val currentModel = generativeModel ?: return // Already checked in caller, but safe

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            
             // Create the first "user" message (containing context) to store in history
            val contextMessage = ChatMessage(formattedPrompt, System.currentTimeMillis(), userPerson, true)
            
            try {
                // Generate response based on the formatted initial prompt ONLY
                 val initialContent = content(role = "user") { text(formattedPrompt) } // Need to create Content
                 val response = currentModel.generateContent(initialContent)
                 aiResponseText = response.text // No need to check for WAIT on initial msg
                 Log.d(TAG, "Raw Gemini Response (initial): $aiResponseText")

                if (aiResponseText == null) {
                     Log.w(TAG, "Gemini initial response was null.")
                     errorMessage = "(Error getting initial response)"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for initial message", e)
                errorMessage = "(API Error: ${e.message})"
            }

            // Add context message AND AI response/error to history
             conversationHistory.add(contextMessage) // Add the context prompt first
            val aiMessageToAdd = if (aiResponseText != null) {
                 Log.i(TAG, "Processed AI Response (initial): $aiResponseText")
                 ChatMessage(aiResponseText, System.currentTimeMillis(), aiPerson, false)
            } else {
                 ChatMessage(errorMessage ?: "(Unknown Error)", System.currentTimeMillis(), aiPerson, false)
            }
            conversationHistory.add(aiMessageToAdd) // Add AI response second
            
            // Update notification to show the initial exchange
            showConversationNotification(appName, sessionTimeMs, dailyTimeMs)
        }
    }

    // Modified to remove the initial message fallback
    private fun showConversationNotification(appName: String = "Unknown App", sessionTimeMs: Long = 0L, dailyTimeMs: Long = 0L) {
        // *** REMOVED: No longer adding default message here ***
        // if (conversationHistory.isEmpty()) { ... }
        
        Log.d(TAG, "showConversationNotification called. History size: ${conversationHistory.size}")
        if (conversationHistory.isEmpty()) {
             Log.w(TAG, "showConversationNotification called with empty history. Notification might appear empty or not show.")
             // Optionally, could cancel the notification if history becomes empty? 
             // notificationManager.cancel(NOTIFICATION_ID)
             // return // Or let it proceed to show an empty notification
        }

        // --- Remote Input Action with Context (Unchanged) --- 
        val remoteInput: RemoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run { setLabel("Reply...").build() }
        val replyIntent = Intent(this, AppUsageMonitorService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            // *** Add context as extras ***
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_SESSION_TIME_MS, sessionTimeMs)
            putExtra(EXTRA_DAILY_TIME_MS, dailyTimeMs)
        }
        val replyPendingIntent: PendingIntent = PendingIntent.getService(this, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action: NotificationCompat.Action = NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // --- Build MessagingStyle (Unchanged) --- 
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
        conversationHistory.forEach { msg ->
            val notificationMessage = NotificationCompat.MessagingStyle.Message(
                msg.text, msg.timestamp, msg.sender
            )
            messagingStyle.addMessage(notificationMessage)
        }
        // ----------------------------
        
        // --- Build Notification (Unchanged) --- 
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Confirm HIGH priority
            .setAutoCancel(false) 
            .setOngoing(false) 
            .addAction(action)
            
         val contentText = conversationHistory.lastOrNull()?.text ?: "Conversation started..." // Better fallback
         notificationBuilder.setContentText(contentText)

         try {
             notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
             Log.d(TAG, "Conversation notification posted/updated successfully.")
         } catch (e: Exception) {
             Log.e(TAG, "Error posting/updating conversation notification", e)
         }
    }

    private fun getReadableAppName(packageName: String?): String {
         return when (packageName) {
            "com.facebook.katana" -> "Facebook"
            "com.instagram.android" -> "Instagram"
            "com.twitter.android" -> "Twitter"
            "com.google.android.youtube" -> "YouTube"
            null -> "Unknown App"
            else -> packageName // Return package name if not in our known list
        }
    }

    private fun updateStatsNotification() {
        Log.d(TAG, "updateStatsNotification called")
        val notification = createStatsNotification()
        try {
            notificationManager.notify(STATS_NOTIFICATION_ID, notification)
            Log.d(TAG, "Stats notification updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stats notification", e)
        }
    }

    private fun createStatsNotification(): android.app.Notification {
        val monitoringStatus = "Monitoring: Active"
        val currentAppName = if (currentApp != null && isWastefulApp(currentApp!!)) {
            when (currentApp) {
                "com.facebook.katana" -> "Facebook"
                "com.instagram.android" -> "Instagram"
                "com.twitter.android" -> "Twitter"
                "com.google.android.youtube" -> "YouTube"
                else -> "Unknown Wasteful App"
            }
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
             // *** Use MIN priority for silent stats ***
            .setPriority(NotificationCompat.PRIORITY_MIN) 
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null) 
            .setVibrate(longArrayOf(0L)) // Ensure vibration is off
            
        return builder.build()
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

    private fun createNotificationChannels() {
        Log.d(TAG, "createNotificationChannels called")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for nagging/reply notifications (HIGH importance for Heads-Up)
             Log.d(TAG, "Creating channel $CHANNEL_ID (High Importance)")
            val name = "Time Linter Conversations"
            val descriptionText = "Notifications about app usage and AI replies"
             // *** Use HIGH importance for Heads-Up ***
            val importance = NotificationManager.IMPORTANCE_HIGH 
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                 // High importance channels usually bypass DND, which might be desired here?
                 // setBypassDnd(true) 
            }
            notificationManager.createNotificationChannel(channel)

            // Channel for PERSISTENT STATS notification (MIN importance and silent)
             Log.d(TAG, "Creating channel $STATS_CHANNEL_ID (Min Importance, Silent)")
            val statsName = "Time Linter Stats"
            val statsDescription = "Persistent notification showing usage statistics"
             // *** Use MIN importance for stats channel ***
            val statsChannel = NotificationChannel(STATS_CHANNEL_ID, statsName, NotificationManager.IMPORTANCE_MIN).apply { 
                description = statsDescription
                setSound(null, null) 
                enableVibration(false)
                setShowBadge(false) 
            }
            notificationManager.createNotificationChannel(statsChannel)
             Log.d(TAG, "Notification channels created.")
        } else {
             // On older versions, control sound/vibration directly on the Notification builder
             Log.d(TAG, "Skipping channel creation (SDK < O)")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        executor.shutdownNow()
        serviceScope.cancel()
        isMonitoringScheduled = false // Reset flag when service is destroyed
    }
} 