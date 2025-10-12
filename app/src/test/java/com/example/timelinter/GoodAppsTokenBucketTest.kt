package com.example.timelinter

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/**
 * Unit tests for TokenBucket logic with Good Apps support.
 * 
 * Good Apps feature:
 * - Using good apps refills the bucket beyond the normal max threshold
 * - Rewards are configurable (default: 10 minutes earned per 5 minutes of use)
 * - Maximum overfill is configurable (default: 30 minutes extra)
 * - When overfilled, the bucket decays over time (default: 10 minutes per hour)
 */
class GoodAppsTokenBucketTest {

    // Helper to convert minutes to milliseconds
    private fun min(minutes: Int): Long = TimeUnit.MINUTES.toMillis(minutes.toLong())

    @Test
    fun testGoodAppReward_BasicAccumulation() {
        // Setup: 5 min max, good app earns 10 min per 5 min used
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at full capacity
        var remaining = min(5)
        var accumulated = 0L
        var goodAppAccumulated = 0L

        // Use good app for 5 minutes
        val result = TokenBucket.update(
            previousRemainingMs = remaining,
            previousAccumulatedNonWastefulMs = accumulated,
            previousGoodAppAccumulatedMs = goodAppAccumulated,
            lastUpdateTimeMs = 0L,
            nowMs = min(5),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should earn 10 minutes reward for 5 minutes of use
        assertEquals(min(15), result.newRemainingMs) // 5 + 10 = 15 (overfilled)
        assertEquals(0L, result.newGoodAppAccumulatedMs) // Consumed the 5 minutes
    }

    @Test
    fun testGoodAppReward_PartialAccumulation() {
        // Use good app for 3 minutes (not enough for reward yet)
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        val result = TokenBucket.update(
            previousRemainingMs = min(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(3),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // No reward yet, just accumulating
        assertEquals(min(5), result.newRemainingMs) // Still at max (can't go higher without reward)
        assertEquals(min(3), result.newGoodAppAccumulatedMs) // 3 minutes accumulated
    }

    @Test
    fun testGoodAppReward_MultipleRewards() {
        // Use good app for 12 minutes = 2 full intervals + 2 min remainder
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        val result = TokenBucket.update(
            previousRemainingMs = min(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(12),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should earn 2 rewards: 2 * 10 = 20 minutes
        assertEquals(min(25), result.newRemainingMs) // 5 + 20 = 25
        assertEquals(min(2), result.newGoodAppAccumulatedMs) // 2 minutes remainder
    }

    @Test
    fun testGoodAppReward_MaxOverfillLimit() {
        // Start already overfilled near the limit
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at 30 minutes (max + maxOverfill = 5 + 30 = 35)
        val result = TokenBucket.update(
            previousRemainingMs = min(30),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(5),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should be clamped to max + maxOverfill = 35 minutes
        assertTrue(result.newRemainingMs <= min(35))
        assertEquals(min(35), result.newRemainingMs)
    }

    @Test
    fun testOverfillDecay_OneHour() {
        // Overfilled bucket should decay by 10 min per hour
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at 15 minutes (10 min overfill), wait 1 hour doing nothing
        val result = TokenBucket.update(
            previousRemainingMs = min(15),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.HOURS.toMillis(1),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Should decay by 10 minutes: 15 - 10 = 5 (back to max)
        // But we also get normal replenishment for the hour
        // 1 hour = 60 minutes, replenish every 5 min with 1 min = 12 replenishments
        // But we're already at max, so replenishment doesn't help
        // Decay should bring us from 15 to 5 (removed 10 min of overfill)
        assertTrue(result.newRemainingMs <= min(15))
        // With decay of 10 min/hour and replenishment of 12 min/hour, net is +2 min
        // But we're capped at max when not using good apps, so should be at 5
        assertEquals(min(5), result.newRemainingMs)
    }

    @Test
    fun testOverfillDecay_PartialHour() {
        // Decay should be proportional to time passed
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at 15 minutes (10 min overfill), wait 30 minutes
        val result = TokenBucket.update(
            previousRemainingMs = min(15),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(30),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Decay: 30 min / 60 min * 10 min = 5 minutes decay
        // Replenish: 30 min / 5 min = 6 intervals * 1 min = 6 minutes
        // But overfill portion decays
        // Start: 15 min (5 base + 10 overfill)
        // After 30 min: 
        //   - Replenish: +6 min
        //   - Decay overfill: -5 min (half hour, half decay)
        // Result: 15 + 6 - 5 = 16 min
        // But actually, decay only applies to the overfill portion
        // Overfill is 10 min, decay 5 min from it = 5 min overfill left
        // Plus base 5 min = 10 min total
        // Plus replenishment: but we're at max, so replenish might not add
        // Let me reconsider the logic...
        // The decay should only affect the amount above max
        val expected = min(10) // 5 base + 5 remaining overfill
        assertEquals(expected, result.newRemainingMs)
    }

    @Test
    fun testOverfillDecay_DoesNotAffectBelowMax() {
        // Decay should only affect overfill, not the base bucket
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at exactly max (no overfill), wait 1 hour
        val result = TokenBucket.update(
            previousRemainingMs = min(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.HOURS.toMillis(1),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Should not decay below max, even with decay enabled
        // Replenishment should keep it at max
        assertEquals(min(5), result.newRemainingMs)
    }

    @Test
    fun testWastefulUsage_ConsumesOverfill() {
        // Using wasteful apps should consume from overfill first
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start overfilled at 15 minutes, use wasteful app for 3 minutes
        val result = TokenBucket.update(
            previousRemainingMs = min(15),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(3),
            isCurrentlyWasteful = true,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Should consume 3 minutes: 15 - 3 = 12
        assertEquals(min(12), result.newRemainingMs)
    }

    @Test
    fun testCombined_GoodAppAndNormalReplenish() {
        // Good app accumulator and normal accumulator should work independently
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // Start at 3 minutes, use good app for 7 minutes
        // This should trigger both normal replenish (every 5 min) and good app reward (every 5 min)
        val result = TokenBucket.update(
            previousRemainingMs = min(3),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(7),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Normal replenish: 7 min / 5 min = 1 interval * 1 min = 1 min
        // Good app reward: 7 min / 5 min = 1 interval * 10 min = 10 min
        // Total: 3 + 1 + 10 = 14 min
        // Remainders: normal = 2 min, good app = 2 min
        assertEquals(min(14), result.newRemainingMs)
        assertEquals(min(2), result.newAccumulatedNonWastefulMs)
        assertEquals(min(2), result.newGoodAppAccumulatedMs)
    }

    @Test
    fun testZeroOverfillConfig_BehavesLikeNormal() {
        // If maxOverfill is 0, good apps should not provide extra capacity
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = 0L, // No overfill allowed
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        val result = TokenBucket.update(
            previousRemainingMs = min(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(5),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should be clamped at max (no overfill)
        assertEquals(min(5), result.newRemainingMs)
    }

    @Test
    fun testZeroDecay_OverfillPersists() {
        // If decay is 0, overfill should not decay
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = 0L, // No decay
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        val result = TokenBucket.update(
            previousRemainingMs = min(15), // Overfilled
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.HOURS.toMillis(1),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Should remain overfilled (no normal replenishment since already above max, no decay)
        // Normal replenishment doesn't apply when above max
        // Decay is 0, so no decay happens
        // Expected: stays at 15
        assertEquals(min(15), result.newRemainingMs)
    }

    @Test
    fun testGoodAppRewardWithCarryover() {
        // Test that good app accumulator carries over across updates
        val config = TokenBucketConfig(
            maxThresholdMs = min(5),
            replenishIntervalMs = min(5),
            replenishAmountMs = min(1),
            maxOverfillMs = min(30),
            overfillDecayPerHourMs = min(10),
            goodAppRewardIntervalMs = min(5),
            goodAppRewardAmountMs = min(10)
        )

        // First update: accumulate 3 minutes
        val result1 = TokenBucket.update(
            previousRemainingMs = min(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = min(3),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        assertEquals(min(5), result1.newRemainingMs)
        assertEquals(min(3), result1.newGoodAppAccumulatedMs)

        // Second update: accumulate 4 more minutes (total 7, should trigger reward)
        val result2 = TokenBucket.update(
            previousRemainingMs = result1.newRemainingMs,
            previousAccumulatedNonWastefulMs = result1.newAccumulatedNonWastefulMs,
            previousGoodAppAccumulatedMs = result1.newGoodAppAccumulatedMs,
            lastUpdateTimeMs = min(3),
            nowMs = min(7),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Total good app time: 3 + 4 = 7 minutes
        // Reward: 7 / 5 = 1 interval * 10 min = 10 min
        // Remaining: 5 + 10 = 15 min
        // Good app accumulator: 7 % 5 = 2 min
        assertEquals(min(15), result2.newRemainingMs)
        assertEquals(min(2), result2.newGoodAppAccumulatedMs)
    }
}



