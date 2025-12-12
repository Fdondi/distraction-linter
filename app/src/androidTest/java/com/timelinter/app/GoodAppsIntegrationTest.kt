package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Instrumented tests for Good Apps integration aligned with the current Duration-based APIs.
 */
@RunWith(AndroidJUnit4::class)
class GoodAppsIntegrationTest {

    private lateinit var context: Context
    private lateinit var fakeTime: FakeTimeProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeTime = FakeTimeProvider(0L)

        // Reset prefs to known values
        GoodAppManager.saveSelectedApps(context, emptySet())
        context.getSharedPreferences("timelinter_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        SettingsManager.setMaxThreshold(context, 5.minutes)
        SettingsManager.setReplenishRateFraction(context, 0.2f) // 12 min/hour
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        SettingsManager.setMaxOverfill(context, 30.minutes)
        SettingsManager.setOverfillDecayPerHour(context, 10.minutes)
        SettingsManager.setGoodAppFillRateMultiplier(context, 2.0f)
        SettingsManager.setNeutralAppFillRateMultiplier(context, 1.0f)
    }

    @After
    fun tearDown() {
        GoodAppManager.saveSelectedApps(context, emptySet())
    }

    @Test
    fun testGoodAppManager_SaveAndRetrieve() {
        val testApps = setOf("com.example.app1", "com.example.app2", "com.example.app3")

        GoodAppManager.saveSelectedApps(context, testApps)
        val retrieved = GoodAppManager.getSelectedApps(context)

        assertEquals(testApps, retrieved)
    }

    @Test
    fun testGoodAppManager_IsGoodApp() {
        val goodApps = setOf("com.example.goodapp")
        GoodAppManager.saveSelectedApps(context, goodApps)

        assertTrue(GoodAppManager.isGoodApp(context, "com.example.goodapp"))
        assertFalse(GoodAppManager.isGoodApp(context, "com.example.badapp"))
    }

    @Test
    fun testGoodAppSettings_DurationsAndMultipliers() {
        SettingsManager.setMaxOverfill(context, 45.minutes)
        SettingsManager.setOverfillDecayPerHour(context, 15.minutes)
        SettingsManager.setGoodAppFillRateMultiplier(context, 2.5f)
        SettingsManager.setNeutralAppFillRateMultiplier(context, 1.2f)

        assertEquals(45.minutes, SettingsManager.getMaxOverfill(context))
        assertEquals(15.minutes, SettingsManager.getOverfillDecayPerHour(context))
        assertEquals(2.5f, SettingsManager.getGoodAppFillRateMultiplier(context))
        assertEquals(1.2f, SettingsManager.getNeutralAppFillRateMultiplier(context))
    }

    @Test
    fun testTokenBucket_GoodAppEarnsTime() {
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        val bucket = TokenBucket(context, fakeTime)

        // First update to set state; delta is zero.
        bucket.update(AppState.GOOD)

        // Advance time and update again to accrue time with multiplier.
        fakeTime.advanceMinutes(5)
        val remaining = bucket.update(AppState.GOOD)

        assertTrue("Good app should increase remaining time", remaining > 5.minutes)
    }

    @Test
    fun testTokenBucket_WastefulConsumesTime() {
        SettingsManager.setThresholdRemaining(context, 10.minutes)
        val bucket = TokenBucket(context, fakeTime)

        // Initialize state
        bucket.update(AppState.WASTEFUL)
        fakeTime.advanceMinutes(3)
        val remaining = bucket.update(AppState.WASTEFUL)

        assertTrue("Wasteful app should reduce remaining time", remaining < 10.minutes)
    }

    @Test
    fun testTokenBucket_OverfillIsClamped() {
        SettingsManager.setThresholdRemaining(context, 34.minutes) // below max (5 + 30)
        val bucket = TokenBucket(context, fakeTime)

        bucket.update(AppState.GOOD)
        fakeTime.advanceMinutes(10)
        val remaining = bucket.update(AppState.GOOD)

        val maxWithOverfill = SettingsManager.getMaxThreshold(context) + SettingsManager.getMaxOverfill(context)
        assertTrue("Remaining time should not exceed max overfill", remaining <= maxWithOverfill)
    }
}
