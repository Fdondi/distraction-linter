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
        Log.d(TAG, "onStartCommand called")
        startMonitoring()
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

    private fun sendNotification() {
        Log.d(TAG, "sendNotification called (nagging notification)")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Time Check")
            .setContentText("Hey! Is this really the best use of your time right now?")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Nagging notification sent successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending nagging notification", e)
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