package com.example.timelinter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TokenBucketTest {

    @Test
    // bucket starts full and drains while wasteful, triggers at zero
    fun bucket_drain() {
        val maxMs = TimeUnit.MINUTES.toMillis(5)
        val config = TokenBucketConfig(
            maxThresholdMs = maxMs,
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10), // Replenish 1 min every 10 mins
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1)
        )

        val t0 = 0L
        var remaining = maxMs
        var accumulatedMs = 0L
        var last = t0

        // After 2 minutes of wasteful usage, remaining should drop by 2 minutes
        val t1 = t0 + TimeUnit.MINUTES.toMillis(2)
        var updateResult = TokenBucket.update(remaining, accumulatedMs, last, t1, true, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        assertEquals("Remaining after 2m wasteful", maxMs - TimeUnit.MINUTES.toMillis(2), remaining)
        assertEquals("Accumulated after 2m wasteful", 0L, accumulatedMs) // Should not change
        last = t1

        // After another 3 minutes, it should hit zero (trigger)
        val t2 = t1 + TimeUnit.MINUTES.toMillis(3)
        updateResult = TokenBucket.update(remaining, accumulatedMs, last, t2, true, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        assertEquals("Remaining after 5m wasteful", 0L, remaining)
        assertEquals("Accumulated after 5m wasteful", 0L, accumulatedMs) // Should not change

        // Should trigger when wasteful, not allowed, remaining <= 0
        assertTrue(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = false, remainingMs = remaining))
    }

    @Test
    // bucket replenishes when not wasteful and accumulator works
    fun bucket_replenish() {
        val maxMs = TimeUnit.MINUTES.toMillis(5)
        val replenishInterval = TimeUnit.MINUTES.toMillis(10)
        val replenishAmount = TimeUnit.MINUTES.toMillis(1)
        val config = TokenBucketConfig(
            maxThresholdMs = maxMs,
            replenishIntervalMs = replenishInterval,
            replenishAmountMs = replenishAmount
        )

        val t0 = 0L
        var remaining = TimeUnit.MINUTES.toMillis(1) // Start low
        var accumulatedMs = 0L
        var last = t0

        // 1. After 5 minutes of non-wasteful usage (half of replenishInterval)
        val t1 = t0 + TimeUnit.MINUTES.toMillis(5)
        var updateResult = TokenBucket.update(remaining, accumulatedMs, last, t1, false, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        assertEquals("Remaining after 5m non-wasteful (no replenish yet)", TimeUnit.MINUTES.toMillis(1), remaining)
        assertEquals("Accumulated after 5m non-wasteful", TimeUnit.MINUTES.toMillis(5), accumulatedMs)
        last = t1

        // 2. After another 5 minutes of non-wasteful usage (total 10 minutes = 1 replenish cycle)
        val t2 = t1 + TimeUnit.MINUTES.toMillis(5)
        updateResult = TokenBucket.update(remaining, accumulatedMs, last, t2, false, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        assertEquals("Remaining after 10m non-wasteful (1 replenish)", TimeUnit.MINUTES.toMillis(1) + replenishAmount, remaining)
        assertEquals("Accumulated after 10m non-wasteful (should be 0)", 0L, accumulatedMs)
        last = t2

        // 3. After 25 minutes of non-wasteful usage (2.5 replenish cycles)
        //    Current state: remaining = 2 mins, accumulated = 0ms
        //    Delta: 25 mins. Accumulated becomes 25 mins.
        //    Replenishments possible: 25 / 10 = 2.
        //    Remaining += 2 * 1_min = 2 + 2 = 4 mins.
        //    Accumulated = 25 % 10 = 5 mins.
        val t3 = t2 + TimeUnit.MINUTES.toMillis(25)
        updateResult = TokenBucket.update(remaining, accumulatedMs, last, t3, false, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        val expectedRemainingAfter25m = TimeUnit.MINUTES.toMillis(2) + (2 * replenishAmount)
        assertEquals("Remaining after 25m more non-wasteful", expectedRemainingAfter25m, remaining)
        assertEquals("Accumulated after 25m more non-wasteful", TimeUnit.MINUTES.toMillis(5), accumulatedMs)
        last = t3
        
        // 4. After 100 more minutes, it should cap at maxMs, accumulator should be remainder
        //    Current state: remaining = 4 mins (240000ms), accumulated = 5 mins (300000ms)
        //    Delta: 100 mins (6000000ms).
        //    New accumulated = 300000ms + 6000000ms = 6300000ms (105 mins)
        //    Replenishments possible: 105 / 10 = 10.
        //    Remaining += 10 * 1_min = 4_mins + 10_mins = 14_mins.
        //    Clamped remaining = maxMs (5 mins).
        //    Accumulated = 105 % 10 = 5 mins (300000ms).
        val t4 = t3 + TimeUnit.MINUTES.toMillis(100) 
        updateResult = TokenBucket.update(remaining, accumulatedMs, last, t4, false, config)
        remaining = updateResult.newRemainingMs
        accumulatedMs = updateResult.newAccumulatedNonWastefulMs
        assertEquals("Remaining after 100m more (capped)", maxMs, remaining)
        assertEquals("Accumulated after 100m more", TimeUnit.MINUTES.toMillis(5), accumulatedMs)
    }
}
