package com.timelinter.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppUsageMonitorServiceLifecycleTest {

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
    fun screenOffBroadcastStopsService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))

        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun unlockReceiverStartsService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = Shadows.shadowOf(context as Application)
        shadowApp.clearStartedServices()

        val receiver = UnlockRestartReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        val started = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(AppUsageMonitorService::class.java.name, started?.component?.className)
    }
}




