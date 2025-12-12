package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ScreenUnlockBehaviorTest {

    private lateinit var context: Context
    private val neutralCategory = ResolvedCategory(
        id = AppCategoryIds.DEFAULT,
        label = "Default",
        minutesChangePerMinute = null,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = false,
        usesNeutralTimers = true
    )
    private val wastefulCategory = ResolvedCategory(
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

        SettingsManager.setMaxThreshold(context, 30.minutes)
        SettingsManager.setReplenishRateFraction(context, 0.5f) // 30 min/hour
        SettingsManager.setThresholdRemaining(context, 0.minutes)
        SettingsManager.setMaxOverfill(context, 30.minutes)
        SettingsManager.setOverfillDecayPerHour(context, 10.minutes)
        SettingsManager.setGoodAppFillRateMultiplier(context, 2.0f)
        SettingsManager.setNeutralAppFillRateMultiplier(context, 1.0f)
    }

    @Test
    fun bucketRefillsAfterLongNeutralPeriod() {
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(neutralCategory)
        time.advanceMinutes(120)
        val remaining = bucket.update(neutralCategory)

        assertEquals(SettingsManager.getMaxThreshold(context), remaining)
    }

    @Test
    fun bucketPartiallyRefillsAfterShortNeutralPeriod() {
        SettingsManager.setThresholdRemaining(context, 5.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(neutralCategory)
        time.advanceMinutes(20)
        val remaining = bucket.update(neutralCategory)

        assertTrue("Remaining time should increase after neutral period", remaining > 5.minutes)
    }

    @Test
    fun bucketConsumesImmediatelyOnWastefulAfterUnlock() {
        SettingsManager.setThresholdRemaining(context, 28.minutes)
        val time = FakeTimeProvider()
        val bucket = TokenBucket(context, time)

        bucket.update(neutralCategory)
        time.advanceMinutes(15)
        val afterNeutral = bucket.update(neutralCategory)

        val beforeWasteful = afterNeutral
        val wastefulRemaining = bucket.update(wastefulCategory)
        assertTrue("Wasteful app should reduce remaining time", wastefulRemaining <= beforeWasteful)
    }
}
