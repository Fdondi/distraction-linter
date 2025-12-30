package com.timelinter.app

import android.app.Notification
import org.robolectric.Shadows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppUsageMonitorServiceNotificationTest {

    private lateinit var service: AppUsageMonitorService

    @Before
    fun setUp() {
        service = Robolectric.buildService(AppUsageMonitorService::class.java)
            .create()
            .get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun statsNotification_hasContentIntentToOpenApp() {
        val notification = service.buildStatsNotificationForTests()

        assertNotNull(notification.contentIntent)
        val shadow = Shadows.shadowOf(notification.contentIntent)
        val target = shadow.savedIntent
        assertEquals(MainActivity::class.java.name, target.component?.className)
    }

    @Test
    fun statsNotification_usesWarningIconWhenAuthExpired() {
        service.markBackendAuthExpiredForTests(true)

        val notification = service.buildStatsNotificationForTests()

        assertIcon(notification, R.drawable.ic_warning_sign)
    }

    @Test
    fun statsNotification_usesDefaultIconWhenAuthHealthy() {
        val notification = service.buildStatsNotificationForTests()

        assertIcon(notification, R.mipmap.ic_launcher)
    }

    private fun assertIcon(notification: Notification, expectedIconRes: Int) {
        assertEquals(expectedIconRes, notification.smallIcon.resId)
    }
}









