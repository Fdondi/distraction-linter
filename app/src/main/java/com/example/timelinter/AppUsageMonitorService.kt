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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.*
import android.app.usage.UsageStats
import android.app.PendingIntent
import androidx.core.app.RemoteInput

class AppUsageMonitorService : Service() {
    private val TAG = "AppUsageMonitorService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var notificationManager: NotificationManager
    private val CHANNEL_ID = "TimeLinterChannel"
    private val STATS_CHANNEL_ID = "TimeLinterStatsChannel"
    private val NOTIFICATION_ID = 1
    private val STATS_NOTIFICATION_ID = 2
    private val lastNotificationTime = AtomicLong(0)
    private val notificationInterval = 10L // seconds
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val dailyStartTime = AtomicLong(getStartOfDay())
    private val sessionWastedTime = AtomicLong(0)
    private val dailyWastedTime = AtomicLong(0)
    private var currentApp: String? = null
    private var lastAppChangeTime = AtomicLong(System.currentTimeMillis())

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
            // Handle other actions if any (e.g., from MainActivity to start/stop)
            else -> {
                 Log.d(TAG, "onStartCommand: Starting monitoring")
                 startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        Log.d(TAG, "startMonitoring called - scheduling checks every 10 seconds")
        executor.scheduleAtFixedRate({
            Log.d(TAG, "Scheduled task running: checkAppUsage and updateStatsNotification")
            checkAppUsage()
            updateStatsNotification()
        }, 0, 10, TimeUnit.SECONDS)
    }

    private fun checkAppUsage() {
        Log.d(TAG, "checkAppUsage started")
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 10

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

        if (isWastefulApp(detectedApp)) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastNotification = currentTime - lastNotificationTime.get()
            
            if (timeSinceLastNotification >= notificationInterval * 1000) {
                Log.d(TAG, "Interval passed, sending nagging notification.")
                sendNotification()
                lastNotificationTime.set(currentTime)
            }
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
                // TODO: Process the reply (send to AI, etc.)

                // For now, let's re-post the original notification without the input action
                // to give feedback that the reply was processed.
                // Ideally, you might show a different notification or update the existing one.
                // A simple approach is to cancel the original and potentially post a new one.
                // Or update the existing one by removing the action / remote input.
                notificationManager.cancel(NOTIFICATION_ID) // Cancel the one with the input
                
                // Optional: Post a temporary confirmation or just let the stats notification remain.
                // Log.d(TAG, "Reply processed. Notification cancelled.")
                
                // Placeholder for sending to AI
                sendToAI("User: $replyText\nAI:") // Example function call
            } else {
                 Log.w(TAG, "Received empty reply.")
            }
        } else {
             Log.w(TAG, "Could not extract remote input from reply intent.")
        }
    }
    
    // Placeholder function for AI interaction
    private fun sendToAI(prompt: String) {
        Log.d(TAG, "Placeholder: Sending to AI:$prompt")
        // Here you would implement the actual API call to Gemini or another AI
        // And potentially update the notification with the AI's response
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
        executor.shutdown()
    }
} 