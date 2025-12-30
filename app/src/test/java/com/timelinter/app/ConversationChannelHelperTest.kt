package com.timelinter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [28])
class ConversationChannelHelperTest {

    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        notificationManager =
                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                        as NotificationManager
        // Clean slate for each test
        notificationManager.deleteNotificationChannel(AppUsageMonitorService.CHANNEL_ID)
    }

    @Test
    fun ensureChannel_createsWhenMissing() {
        val context = RuntimeEnvironment.getApplication()

        val channel = ConversationChannelHelper.ensureChannel(context)

        assertNotNull(channel)
        assertEquals(AppUsageMonitorService.CHANNEL_ID, channel?.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel?.importance)
    }

    @Test
    fun ensureChannel_preservesExistingImportance() {
        val context = RuntimeEnvironment.getApplication()
        val preExisting =
                NotificationChannel(
                        AppUsageMonitorService.CHANNEL_ID,
                        "Conversation",
                        NotificationManager.IMPORTANCE_LOW
                )
        notificationManager.createNotificationChannel(preExisting)

        val channel = ConversationChannelHelper.ensureChannel(context)

        assertSame(preExisting.importance, channel?.importance)
    }

    @Test
    fun getChannelImportance_returnsImportance() {
        val context = RuntimeEnvironment.getApplication()
        ConversationChannelHelper.ensureChannel(context)

        val importance = ConversationChannelHelper.getChannelImportance(context)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, importance)
    }
}


















