package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TokenBucketTest {

    private lateinit var context: Context
    private val wastefulCategory = ResolvedCategory(
        id = AppCategoryIds.BAD,
        label = "Bad",
        minutesChangePerMinute = -1f,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = false,
        usesNeutralTimers = false
    )
    private val neutralCategory = ResolvedCategory(
        id = AppCategoryIds.DEFAULT,
        label = "Default",
        minutesChangePerMinute = null,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = false,
        usesNeutralTimers = true
    )

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
    fun bucket_drains_when_wasteful_category_active() {
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(wastefulCategory) // establish state
        time.advanceMinutes(2)
        var remaining = bucket.update(wastefulCategory)
        assertEquals("Remaining after 2m wasteful", 3.minutes, remaining)

        time.advanceMinutes(3)
        remaining = bucket.update(wastefulCategory)
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
    fun bucket_replenishes_when_neutral_category() {
        SettingsManager.setThresholdRemaining(context, 1.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(neutralCategory)
        time.advanceMinutes(5)
        var remaining = bucket.update(neutralCategory)
        assertEquals("Replenish proportionally at 5 minutes", 90.seconds, remaining)

        time.advanceMinutes(5)
        remaining = bucket.update(neutralCategory)
        assertEquals("One replenish after full interval", 2.minutes, remaining)

        time.advanceMinutes(100)
        remaining = bucket.update(neutralCategory)
        assertEquals("Capped at max threshold", SettingsManager.getMaxThreshold(context), remaining)
    }
}
