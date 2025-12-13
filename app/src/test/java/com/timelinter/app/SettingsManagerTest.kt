package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SettingsManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("timelinter_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun wakeupInterval_defaultsAndPersists() {
        assertEquals(30.seconds, SettingsManager.getWakeupInterval(context))

        SettingsManager.setWakeupInterval(context, 2.minutes)
        assertEquals(2.minutes, SettingsManager.getWakeupInterval(context))
    }

}

