package com.timelinter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AppContinuityManagerTest {

    private class FakeTimeProvider(start: Instant = Instant.fromEpochMilliseconds(0)) : TimeProvider {
        private var now: Instant = start
        override fun now(): Instant = now
        fun advance(duration: Duration) { now += duration }
    }

    private val neutralCategory = ResolvedCategory(
        id = AppCategoryIds.DEFAULT,
        label = "Default",
        minutesChangePerMinute = null,
        freeMinutesPerPeriod = 0,
        freePeriodsPerDay = 0,
        allowOverfill = false,
        usesNeutralTimers = true
    )

    private fun app(packageName: String) = ForegroundAppInfo(
        packageName = packageName,
        readableName = packageName,
        category = neutralCategory
    )

    private lateinit var time: FakeTimeProvider
    private lateinit var manager: AppContinuityManager

    @Before
    fun setUp() {
        time = FakeTimeProvider()
        manager = AppContinuityManager(
            timeProvider = time,
            noAppGapDurationProvider = { 2.minutes },
            freeBucketResumeDurationProvider = { 10.minutes }
        )
    }

    @Test
    fun keepsSameAppWhenNoAppReturnsQuickly() {
        var active: ForegroundAppInfo? = app("a.example")

        val first = manager.resolve(active, detectedApp = null, currentAppIsFree = false)
        assertEquals("a.example", first?.packageName)

        time.advance(90.seconds)
        active = first

        val stillHeld = manager.resolve(active, detectedApp = null, currentAppIsFree = false)
        assertEquals("a.example", stillHeld?.packageName)

        time.advance(30.seconds)
        active = stillHeld

        val backAgain = manager.resolve(active, detectedApp = app("a.example"), currentAppIsFree = false)
        assertEquals("a.example", backAgain?.packageName)
    }

    @Test
    fun clearsAppAfterExtendedNoAppGap() {
        var active: ForegroundAppInfo? = app("lost.app")

        time.advance(3.minutes)
        val dropped = manager.resolve(active, detectedApp = null, currentAppIsFree = false)
        assertNull(dropped)
        active = dropped

        val newDetect = manager.resolve(active, detectedApp = app("lost.app"), currentAppIsFree = false)
        assertEquals("lost.app", newDetect?.packageName)
    }

    @Test
    fun holdsFreeAppThroughTemporaryOtherDetection() {
        var active: ForegroundAppInfo? = app("free.app")

        val held = manager.resolve(active, detectedApp = app("intruder.app"), currentAppIsFree = true)
        assertEquals("free.app", held?.packageName)
        active = held

        time.advance(5.minutes)
        val stillHeld = manager.resolve(active, detectedApp = app("intruder.app"), currentAppIsFree = true)
        assertEquals("free.app", stillHeld?.packageName)
        active = stillHeld

        time.advance(1.minutes)
        val back = manager.resolve(active, detectedApp = app("free.app"), currentAppIsFree = true)
        assertEquals("free.app", back?.packageName)
    }

    @Test
    fun switchesWhenFreeResumeWindowExpires() {
        var active: ForegroundAppInfo? = app("free.app")

        val held = manager.resolve(active, detectedApp = app("long.running"), currentAppIsFree = true)
        assertEquals("free.app", held?.packageName)
        active = held

        time.advance(11.minutes)
        val switched = manager.resolve(active, detectedApp = app("long.running"), currentAppIsFree = true)
        assertEquals("long.running", switched?.packageName)
    }
}










