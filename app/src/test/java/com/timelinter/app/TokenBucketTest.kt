package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TokenBucketTest {

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
        SettingsManager.setReplenishRateFraction(context, 0.1f) // 6 min/hour
        SettingsManager.setThresholdRemaining(context, 5.minutes)
    }

    @Test
    fun bucket_drains_whenWasteful() {
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(AppState.WASTEFUL) // establish state
        time.advanceMinutes(2)
        var remaining = bucket.update(AppState.WASTEFUL)
        assertEquals("Remaining after 2m wasteful", 3.minutes, remaining)

        time.advanceMinutes(3)
        remaining = bucket.update(AppState.WASTEFUL)
        assertEquals("Remaining after total 5m wasteful", 0.minutes, remaining)

        assertTrue(
            TriggerDecider.shouldTrigger(
                isWasteful = true,
                isAllowed = false,
                remainingTime = remaining
            )
        )
    }

    @Test
    fun bucket_replenishes_whenNeutral() {
        SettingsManager.setThresholdRemaining(context, 1.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(AppState.NEUTRAL)
        time.advanceMinutes(5)
        var remaining = bucket.update(AppState.NEUTRAL)
        assertEquals("Replenish proportionally at 5 minutes", 90.seconds, remaining)

        time.advanceMinutes(5)
        remaining = bucket.update(AppState.NEUTRAL)
        assertEquals("One replenish after full interval", 2.minutes, remaining)

        time.advanceMinutes(100)
        remaining = bucket.update(AppState.NEUTRAL)
        assertEquals("Capped at max threshold", SettingsManager.getMaxThreshold(context), remaining)
    }
}
