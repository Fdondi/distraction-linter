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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        initializeGeminiModel() // Attempt to initialize model on create

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
                    modelName = "gemini-1.5-flash-latest", // Or another suitable model
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
                handleReply(intent)
                // Reset nagging timer after reply
                lastNaggingNotificationTime.set(0)
            }
            else -> {
                 Log.d(TAG, "onStartCommand: Default action - ensuring monitoring is started.")
                 // Call startMonitoring unconditionally (it will handle duplicates)
                 startMonitoring()
            }
        }
        // Ensure model is initialized if key was added after service started
        if (generativeModel == null) {
             initializeGeminiModel()
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
                    if (recentStat == null || usageStats.lastTimeUsed > recentStat.lastTimeUsed) {
                        recentStat = usageStats
                    }
                }
                foregroundApp = recentStat?.packageName
            }
            Log.d(TAG, "Foreground app detected via queryUsageStats: $foregroundApp (Current: $currentApp)")

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error querying usage stats", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage stats", e)
            return
        }

        val detectedApp = foregroundApp ?: ""

        var timeSpent = 0L
        if (detectedApp != currentApp) {
            Log.d(TAG, "App changed from '$currentApp' to '$detectedApp'")
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

            if (isWastefulApp(detectedApp)) {
                sessionWastedTime.set(0)
                sessionStartTime.set(System.currentTimeMillis())
                Log.d(TAG, "New wasteful app detected: $detectedApp. Reset session timer.")
            } else {
                sessionWastedTime.set(0)
                Log.d(TAG, "New non-wasteful app detected: $detectedApp. Reset session wasted time.")
            }
        } else if (currentApp != null && isWastefulApp(currentApp!!)) {
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

        // Send nagging notification ONLY if interval has passed AND wasteful app is active
        if (isWastefulApp(detectedApp)) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastNag = currentTime - lastNaggingNotificationTime.get()

            if (timeSinceLastNag >= naggingIntervalSeconds * 1000) {
                Log.d(TAG, "Nagging interval ($naggingIntervalSeconds s) passed, sending inline reply notification.")
                sendNotification() // This posts the notification with the reply action
                lastNaggingNotificationTime.set(currentTime) // Reset timer for the nagging notification
            } else {
                 Log.v(TAG, "Still within nagging interval ($timeSinceLastNag ms < ${naggingIntervalSeconds * 1000} ms)")
            }
        } else {
            // If the current app is not wasteful, reset the nagging timer 
            // so the nag appears quickly if they switch back to a wasteful one.
            // Alternatively, keep the timer running if you want 30s gap regardless.
             lastNaggingNotificationTime.set(0) // Resetting means nag is ready if they switch back
             Log.v(TAG, "Non-wasteful app active, resetting nagging timer.")
        }
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
                notificationManager.cancel(NOTIFICATION_ID) // Cancel the input notification

                // Get context for the AI prompt
                val sessionMs = sessionWastedTime.get()
                val dailyMs = dailyWastedTime.get()
                val appName = getReadableAppName(currentApp)

                // Construct the prompt and send to AI
                sendToAI(appName, sessionMs, dailyMs, replyText)

            } else {
                 Log.w(TAG, "Received empty reply.")
            }
        } else {
             Log.w(TAG, "Could not extract remote input from reply intent.")
        }
    }

    // Function for AI interaction
    private fun sendToAI(appName: String, sessionTimeMs: Long, dailyTimeMs: Long, userReply: String) {
         if (generativeModel == null) {
             Log.e(TAG, "Cannot send to AI: GenerativeModel not initialized (likely missing API Key).")
             // Optionally, notify user via a Toast or a simple notification?
             return
         }
         
         // Construct the prompt
         val prompt = """
         Context:
         - Currently using app: $appName
         - Time wasted in this session: ${formatDuration(sessionTimeMs)}
         - Total time wasted today: ${formatDuration(dailyTimeMs)}
         
         User's response to being asked if using the app was worth it: "$userReply"
         
         Your Role: Act as a friendly but firm time management coach. Briefly acknowledge the user's reply and gently challenge them or offer a very short perspective based on the context. Keep your response concise (1-2 sentences).
         
         AI Response:
         """.trimIndent()

        Log.d(TAG, "Sending prompt to Gemini: $prompt")

        // Launch coroutine for the API call
        serviceScope.launch {
            try {
                // Ensure model is not null again just in case
                 val model = generativeModel ?: run {
                    Log.e(TAG, "Coroutine: GenerativeModel became null unexpectedly.")
                    return@launch
                 }
                 
                val response = model.generateContent(prompt)

                val aiResponseText = response.text
                if (aiResponseText != null) {
                    Log.i(TAG, "Gemini Response: $aiResponseText")
                    // TODO: Update the UI - Post a new notification with the AI's reply?
                    // For now, just logging.
                    // Example: postAiReplyNotification(aiResponseText)
                } else {
                    Log.w(TAG, "Gemini response was empty or not text.")
                    // Handle cases where the response might be blocked etc.
                    // Log.w(TAG, "Full Response: ${response}") // Log full response for debugging
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API", e)
                // Handle API call errors (network issues, invalid key, etc.)
            }
        }
    }

    private fun sendNotification() {
        Log.d(TAG, "sendNotification called (prompting for inline reply)")

        val sessionMs = sessionWastedTime.get()
        val dailyMs = dailyWastedTime.get()
        val appName = getReadableAppName(currentApp)

        // 1. Define RemoteInput
        val remoteInput: RemoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run {
            setLabel("Your thoughts?")
            build()
        }

        // 2. Create PendingIntent for the reply action
        val replyIntent = Intent(this, AppUsageMonitorService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            // We might need to pass notification ID or other context here if needed later
        }
        val replyPendingIntent: PendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID, // Use notification ID as request code for uniqueness
            replyIntent,
             // Use FLAG_UPDATE_CURRENT so extras are updated if notification reposts
             // FLAG_MUTABLE is required for RemoteInput
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 3. Create Notification Action with RemoteInput
        val action: NotificationCompat.Action = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground, // Use an appropriate icon
            "Reply",
            replyPendingIntent
        )
        .addRemoteInput(remoteInput)
        .build()

        // 4. Build the notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Time Check")
            .setContentText("Spending time on $appName. Worth it?") // Shorter text
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Add the inline reply action
            .addAction(action)
            .setAutoCancel(true) // Automatically dismiss after action (like reply)
            .build()

        try {
            // Use NOTIFICATION_ID for this notification
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Inline reply notification sent successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending inline reply notification", e)
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