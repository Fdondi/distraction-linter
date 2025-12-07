package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * JVM-level sanity checks for the Duration-based TokenBucket good-app behavior.
 */
class GoodAppsTokenBucketTest {

    private lateinit var context: Context

    private class FakeTimeProvider(start: Instant = Instant.fromEpochMilliseconds(0)) : TimeProvider {
        private var now: Instant = start
        override fun now(): Instant = now
        fun advanceMinutes(minutes: Long) { now += minutes.minutes }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("timelinter_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        SettingsManager.setMaxThreshold(context, 5.minutes)
        SettingsManager.setReplenishInterval(context, 5.minutes)
        SettingsManager.setReplenishAmount(context, 1.minutes)
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        SettingsManager.setMaxOverfill(context, 30.minutes)
        SettingsManager.setOverfillDecayPerHour(context, 10.minutes)
        SettingsManager.setGoodAppFillRateMultiplier(context, 2.0f)
        SettingsManager.setNeutralAppFillRateMultiplier(context, 1.0f)
    }

    @Test
    fun goodAppRefillsAboveMax() {
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(AppState.GOOD)
        time.advanceMinutes(5)
        val remaining = bucket.update(AppState.GOOD)

        assertTrue("Good app should increase remaining time", remaining > 5.minutes)
    }

    @Test
    fun wastefulConsumesTime() {
        SettingsManager.setThresholdRemaining(context, 10.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(AppState.WASTEFUL)
        time.advanceMinutes(3)
        val remaining = bucket.update(AppState.WASTEFUL)

        assertTrue("Wasteful usage should reduce remaining time", remaining < 10.minutes)
    }

    @Test
    fun overfillClampedToConfiguredMax() {
        SettingsManager.setThresholdRemaining(context, 34.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(AppState.GOOD)
        time.advanceMinutes(20)
        val remaining = bucket.update(AppState.GOOD)

        val maxWithOverfill = SettingsManager.getMaxThreshold(context) + SettingsManager.getMaxOverfill(context)
        assertTrue("Overfill should not exceed configured max", remaining <= maxWithOverfill)
    }
}
