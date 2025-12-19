package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TokenBucketCategoryBehaviorTest {

    private lateinit var context: Context

    private class FakeTimeProvider(start: Instant = Instant.fromEpochMilliseconds(0)) : TimeProvider {
        private var now: Instant = start
        override fun now(): Instant = now
        fun advanceMinutes(minutes: Long) {
            now += minutes.minutes
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("timelinter_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        SettingsManager.setMaxThreshold(context, 5.minutes)
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        SettingsManager.setMaxOverfill(context, 15.minutes)
        SettingsManager.setReplenishRateFraction(context, 0.1f)
    }

    @Test
    fun bucket_drains_with_category_rate() {
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)
        val bad = ResolvedCategory(
            id = AppCategoryIds.BAD,
            label = "Bad",
            minutesChangePerMinute = -1f,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = false
        )

        bucket.update(bad) // establish state
        time.advanceMinutes(2)
        val remaining = bucket.update(bad)

        assertEquals(3.minutes, remaining)
    }

    @Test
    fun bucket_replenishes_with_positive_category_and_respects_overfill_limit() {
        SettingsManager.setThresholdRemaining(context, Duration.ZERO)
        SettingsManager.setMaxThreshold(context, 10.minutes)
        SettingsManager.setMaxOverfill(context, 10.minutes)

        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)
        val good = ResolvedCategory(
            id = AppCategoryIds.GOOD,
            label = "Good",
            minutesChangePerMinute = 5f,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = true,
            usesNeutralTimers = false
        )

        bucket.update(good)
        time.advanceMinutes(10)
        val remaining = bucket.update(good)

        assertEquals(20.minutes, remaining) // 10 minutes * 5 rate capped by threshold + overfill (20m)
    }

    @Test
    fun default_category_uses_neutral_settings() {
        SettingsManager.setThresholdRemaining(context, 1.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)
        val defaultCategory = ResolvedCategory(
            id = AppCategoryIds.DEFAULT,
            label = "Default",
            minutesChangePerMinute = null,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = true
        )

        bucket.update(defaultCategory)
        time.advanceMinutes(5)
        val replenished = bucket.update(defaultCategory)

        // replenish rate fraction 0.1 means 0.5 minutes restored in 5 minutes (6 min/hr)
        assertEquals(90.seconds, replenished) // 1.5 minutes total remaining
    }

    @Test
    fun neutral_category_refills_after_switch_from_draining() {
        SettingsManager.setReplenishRateFraction(context, 0.5f) // 30 minutes/hour
        SettingsManager.setMaxThreshold(context, 30.minutes)
        SettingsManager.setThresholdRemaining(context, Duration.ZERO)

        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        val wasteful = ResolvedCategory(
            id = AppCategoryIds.BAD,
            label = "Bad",
            minutesChangePerMinute = -1f,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = false
        )
        val neutral = ResolvedCategory(
            id = AppCategoryIds.DEFAULT,
            label = "Default",
            minutesChangePerMinute = null,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = true
        )

        bucket.update(wasteful) // establish state as draining
        time.advanceMinutes(60) // phone idle / default category for an hour
        val remaining = bucket.update(neutral)

        assertEquals(
            "Bucket should refill to max after long neutral period even when category changed",
            SettingsManager.getMaxThreshold(context),
            remaining
        )
    }
}

