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

// Simple data class for messages
data class ChatMessage(val text: CharSequence, val timestamp: Long, val sender: Person, val isUser: Boolean)

class AppUsageMonitorService : Service() {
    private val TAG = "AppUsageMonitorService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var notificationManager: NotificationManager
    private val CHANNEL_ID = "TimeLinterChannel"
    private val STATS_CHANNEL_ID = "TimeLinterStatsChannel"
    private val NOTIFICATION_ID = 1
    private val STATS_NOTIFICATION_ID = 2
    
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

    // Gemini Model (initialized lazily or when key is available)
    private var generativeModel: GenerativeModel? = null

    // Use volatile for thread safety, though AtomicBoolean is often preferred
    @Volatile private var isMonitoringScheduled = false

    // Action for launching discussion activity
    companion object {
        const val ACTION_DISCUSS_TIME = "com.example.timelinter.ACTION_DISCUSS_TIME"
        const val EXTRA_APP_NAME = "com.example.timelinter.EXTRA_APP_NAME"
        const val EXTRA_SESSION_TIME_MS = "com.example.timelinter.EXTRA_SESSION_TIME_MS"
        const val EXTRA_DAILY_TIME_MS = "com.example.timelinter.EXTRA_DAILY_TIME_MS"
        const val ACTION_HANDLE_REPLY = "com.example.timelinter.ACTION_HANDLE_REPLY"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }

    // Define Persons for MessagingStyle
    private lateinit var userPerson: Person
    private lateinit var aiPerson: Person

    // Store the current conversation thread (simple approach)
    private val conversationHistory = mutableListOf<ChatMessage>()

    // Store context for the *last sent* nagging notification
    private var lastNagContext: NagContext? = null
    data class NagContext(val appName: String, val sessionTimeMs: Long, val dailyTimeMs: Long)

    // Track previous state for clearing history
    @Volatile private var wasPreviouslyWasteful = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        initializeGeminiModel()

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

    private fun initializeGeminiModel() {
        val apiKey = ApiKeyManager.getKey(this)
        if (!apiKey.isNullOrEmpty()) {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-2.0-flash-lite",
                    apiKey = apiKey
                    // Add safetySettings and generationConfig if needed
                )
                Log.i(TAG, "GenerativeModel initialized successfully.")
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
        when (intent?.action) {
            ACTION_HANDLE_REPLY -> {
                // Pass the stored context when handling reply
                handleReply(intent, lastNagContext)
                lastNaggingNotificationTime.set(0) 
            }
            else -> {
                Log.d(TAG, "onStartCommand: Default action - ensuring monitoring is started.")
                startMonitoring()
            }
        }
        if (generativeModel == null) { initializeGeminiModel() }
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

        // *** Clear history on transition from wasteful to non-wasteful ***
        if (wasPreviouslyWasteful && !isCurrentlyWasteful) {
            Log.i(TAG, "Transition from wasteful to non-wasteful app detected. Clearing conversation history.")
            conversationHistory.clear()
            lastNagContext = null // Clear context too
            // Optionally cancel the conversation notification if it's showing
            // notificationManager.cancel(NOTIFICATION_ID)
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
            val currentTime = System.currentTimeMillis()
            val timeSinceLastNag = currentTime - lastNaggingNotificationTime.get()

            if (timeSinceLastNag >= naggingIntervalSeconds * 1000) {
                Log.d(TAG, "Nagging interval passed for wasteful app '$detectedApp'. Showing/Updating conversation notification.")
                // Store context for potential replies
                lastNagContext = NagContext(
                     appName = getReadableAppName(detectedApp),
                     sessionTimeMs = sessionWastedTime.get(),
                     dailyTimeMs = dailyWastedTime.get()
                 )
                sendNotification() // Calls showConversationNotification without clearing history
                lastNaggingNotificationTime.set(currentTime)
            } else { /* Log still within interval */ }
        } else {
             // Reset nag timer immediately if not wasteful
             lastNaggingNotificationTime.set(0)
             // Log.v(TAG, "Non-wasteful app active, resetting nagging timer.")
        }
        
        // *** Update previous state for next check ***
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

    private fun handleReply(intent: Intent, context: NagContext?) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()
            if (!replyText.isNullOrEmpty()) {
                Log.i(TAG, "User replied: '$replyText'")

                if (context == null) {
                    Log.e(TAG, "Cannot process reply: Context from original notification is missing.")
                     // Show error state in notification?
                     conversationHistory.add(ChatMessage("(Error: Missing context for reply)", System.currentTimeMillis(), aiPerson, false))
                     showConversationNotification() // Update notification with error
                     return
                }

                // Add user message to history
                val userMessage = ChatMessage(replyText, System.currentTimeMillis(), userPerson, true)
                conversationHistory.add(userMessage)

                // Send to AI using the *original* context
                // The AI function will call showConversationNotification after completion/error
                sendToAI(context.appName, context.sessionTimeMs, context.dailyTimeMs, replyText)

            } else { Log.w(TAG, "Received empty reply.") }
        } else { Log.w(TAG, "Could not extract remote input from reply intent.") }
    }

    // Function for AI interaction
    private fun sendToAI(appName: String, sessionTimeMs: Long, dailyTimeMs: Long, userReply: String) {
        if (generativeModel == null) {
            Log.e(TAG, "Cannot send to AI: GenerativeModel not initialized.")
            conversationHistory.add(ChatMessage("(Error: AI not ready)", System.currentTimeMillis(), aiPerson, false))
            showConversationNotification() // Show error
            return
        }
        
        // --- Construct prompt (Consider adding history here later) ---
        val basePrompt = """
        Context:
         - Currently using app: $appName
         - Time wasted in this session: ${formatDuration(sessionTimeMs)}
         - Total time wasted today: ${formatDuration(dailyTimeMs)}
         
         User's response to being asked if using the app was worth it: "$userReply"
         
         Your Role: Act as a friendly but firm time management coach. Briefly acknowledge the user's reply and gently challenge them or offer a very short perspective based on the context. Keep your response concise (1-2 sentences).
         
         AI Response:
         """.trimIndent()
         
         // Simple history injection (can be improved)
         // val historyString = conversationHistory.takeLast(4).joinToString("\n") { "${if(it.isUser) "User" else "AI"}: ${it.text}" }
         // val fullPrompt = "$historyString\n\n$basePrompt" 
         val fullPrompt = basePrompt // Keep it simple for now

        Log.d(TAG, "Sending prompt to Gemini: $fullPrompt")
        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            try {
                val model = generativeModel ?: throw IllegalStateException("Model became null unexpectedly")
                val response = model.generateContent(fullPrompt)
                aiResponseText = response.text
                if (aiResponseText == null) {
                    Log.w(TAG, "Gemini response was empty or not text.")
                    errorMessage = "(Error getting response)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API", e)
                errorMessage = "(API Error: ${e.message})"
            }

            // Add AI response or error message to history
            val messageToAdd = if (aiResponseText != null) {
                 Log.i(TAG, "Gemini Response: $aiResponseText")
                 ChatMessage(aiResponseText, System.currentTimeMillis(), aiPerson, false)
            } else {
                 ChatMessage(errorMessage ?: "(Unknown Error)", System.currentTimeMillis(), aiPerson, false)
            }
             conversationHistory.add(messageToAdd)
            
            // *** Update notification ONCE here after processing is complete ***
            showConversationNotification()
        }
    }

    // Initial/Recurring notification prompt during wasteful use
    private fun sendNotification() {
        // REMOVED: conversationHistory.clear()
        // REMOVED: Adding initial message here
        Log.d(TAG, "sendNotification called: Triggering update/display of conversation notification.")
        // Just show the current state of the conversation history
        showConversationNotification()
    }

    // Builds and posts/updates the conversation notification
    private fun showConversationNotification() {
        // Add initial prompt IF history is empty
        if (conversationHistory.isEmpty()) {
             Log.d(TAG, "Conversation history empty, adding initial prompt.")
             val initialMessage = ChatMessage("How is it going?", System.currentTimeMillis(), aiPerson, false)
             conversationHistory.add(initialMessage)
        }
        // Log after potential modification
        Log.d(TAG, "showConversationNotification called. History size: ${conversationHistory.size}")
        val currentSessionAppName = getReadableAppName(currentApp ?: "Unknown")

        // --- Remote Input Action --- 
        val remoteInput: RemoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run {
            setLabel("Reply...")
            build()
        }
        val replyIntent = Intent(this, AppUsageMonitorService::class.java).apply {
            action = ACTION_HANDLE_REPLY
        }
        val replyPendingIntent: PendingIntent = PendingIntent.getService(this, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action: NotificationCompat.Action = NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
        // -----------------------------------------------

        // --- Build MessagingStyle --- 
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
             // Title might need adjustment if currentApp is null briefly
            .setConversationTitle("Time Coach ($currentSessionAppName)") 
            .setGroupConversation(false)
        
        conversationHistory.forEach { msg ->
            val notificationMessage = NotificationCompat.MessagingStyle.Message(
                msg.text, msg.timestamp, msg.sender
            )
            messagingStyle.addMessage(notificationMessage)
        }
        // ----------------------------
        
        // --- Build Notification --- 
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(action)
            
         // Update content text based on last message
         val contentText = conversationHistory.lastOrNull()?.text ?: "Time check..."
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
        return NotificationCompat.Builder(this, STATS_CHANNEL_ID)
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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
            Log.d(TAG, "Creating channel $CHANNEL_ID")
            val name = "Time Linter Notifications"
            val descriptionText = "Notifications about app usage"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Creating channel $STATS_CHANNEL_ID")
            val statsName = "Time Linter Stats"
            val statsDescription = "Persistent notification showing usage statistics"
            val statsChannel = NotificationChannel(STATS_CHANNEL_ID, statsName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = statsDescription
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(statsChannel)
            Log.d(TAG, "Notification channels created.")
        } else {
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