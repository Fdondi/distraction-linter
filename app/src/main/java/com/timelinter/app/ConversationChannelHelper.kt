package com.timelinter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

internal object ConversationChannelHelper {

    private const val CHANNEL_NAME = "Conversation"
    private const val CHANNEL_DESCRIPTION = "Time Linter conversation notifications"

    fun ensureChannel(context: Context): NotificationChannel? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        ?: return null

        val existing = notificationManager.getNotificationChannel(AppUsageMonitorService.CHANNEL_ID)
        if (existing != null) return existing

        val channel =
                NotificationChannel(
                                AppUsageMonitorService.CHANNEL_ID,
                                CHANNEL_NAME,
                                NotificationManager.IMPORTANCE_HIGH
                        )
                        .apply {
                            description = CHANNEL_DESCRIPTION
                            enableVibration(true)
                            setShowBadge(true)
                        }

        notificationManager.createNotificationChannel(channel)
        return notificationManager.getNotificationChannel(AppUsageMonitorService.CHANNEL_ID)
    }

    fun getChannelImportance(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_HIGH
        val channel = ensureChannel(context) ?: return null
        return channel.importance
    }
}










