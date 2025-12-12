package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CategoryAllowanceTrackerTest {

    private lateinit var context: Context
    private lateinit var tracker: CategoryAllowanceTracker
    private lateinit var time: FakeTimeProvider

    private val suspectCategory = ResolvedCategory(
        id = AppCategoryIds.SUSPECT,
        label = "Suspect",
        minutesChangePerMinute = -0.2f,
        freeMinutesPerPeriod = 10,
        freePeriodsPerDay = 2,
        allowOverfill = false,
        usesNeutralTimers = false
    )

    private class FakeTimeProvider(start: Instant = Instant.fromEpochMilliseconds(0)) : TimeProvider {
        private var now: Instant = start
        override fun now(): Instant = now
        fun advanceMinutes(minutes: Long) { now += minutes.minutes }
        fun advanceDays(days: Long) { now += (days * 24 * 60).minutes }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("category_allowance_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        time = FakeTimeProvider()
        tracker = CategoryAllowanceTracker(prefs, time)
        tracker.resetForTests()
    }

    @Test
    fun starts_free_windows_until_daily_limit() {
        val first = tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        assertEquals(10.minutes, first)

        // Still in the free window shortly after start
        time.advanceMinutes(5)
        assertTrue(tracker.isInFreeWindow("com.example.app", time.now()))

        val second = tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        assertEquals(10.minutes, second)

        val third = tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        assertNull(third)
    }

    @Test
    fun resets_daily_usage_on_new_day() {
        tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        assertNull(tracker.startFreeWindowIfEligible("com.example.app", suspectCategory))

        // Move to a new day and confirm allowance resets
        time.advanceDays(1)
        val renewed = tracker.startFreeWindowIfEligible("com.example.app", suspectCategory)
        assertEquals(10.minutes, renewed)
    }

    @Test
    fun returns_null_when_category_has_no_free_periods() {
        val neutral = suspectCategory.copy(
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0
        )

        val window = tracker.startFreeWindowIfEligible("com.example.nope", neutral)
        assertNull(window)
        assertEquals(false, tracker.isInFreeWindow("com.example.nope", time.now()))
    }
}

