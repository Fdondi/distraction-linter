package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * JVM-level sanity checks for the Duration-based TokenBucket category behavior.
 */
class GoodAppsTokenBucketTest {

    private lateinit var context: Context
    private val goodWithOverfill = ResolvedCategory(
        id = AppCategoryIds.GOOD,
        label = "Good",
        minutesChangePerMinute = 5f,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = true,
        usesNeutralTimers = false
    )
    private val goodWithoutOverfill = goodWithOverfill.copy(allowOverfill = false)
    private val badCategory = ResolvedCategory(
        id = AppCategoryIds.BAD,
        label = "Bad",
        minutesChangePerMinute = -1f,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = false,
        usesNeutralTimers = false
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
        SettingsManager.setReplenishRateFraction(context, 0.2f) // 12 min/hour (neutral usage)
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        SettingsManager.setMaxOverfill(context, 30.minutes)
        SettingsManager.setOverfillDecayPerHour(context, 10.minutes)
    }

    @Test
    fun positive_category_refills_up_to_overfill_limit() {
        SettingsManager.setThresholdRemaining(context, Duration.ZERO)
        SettingsManager.setMaxThreshold(context, 5.minutes)
        SettingsManager.setMaxOverfill(context, 10.minutes)

        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(goodWithOverfill)
        time.advanceMinutes(3)
        val remaining = bucket.update(goodWithOverfill)

        val maxWithOverfill = SettingsManager.getMaxThreshold(context) + SettingsManager.getMaxOverfill(context)
        assertEquals(maxWithOverfill, remaining)
    }

    @Test
    fun wasteful_category_consumes_time_at_rate() {
        SettingsManager.setThresholdRemaining(context, 10.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(badCategory)
        time.advanceMinutes(3)
        val remaining = bucket.update(badCategory)

        assertEquals(7.minutes, remaining)
    }

    @Test
    fun positive_category_without_overfill_clamps_to_threshold() {
        SettingsManager.setThresholdRemaining(context, 2.minutes)
        SettingsManager.setMaxThreshold(context, 5.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(goodWithoutOverfill)
        time.advanceMinutes(10)
        val remaining = bucket.update(goodWithoutOverfill)

        assertEquals(SettingsManager.getMaxThreshold(context), remaining)
    }
}
