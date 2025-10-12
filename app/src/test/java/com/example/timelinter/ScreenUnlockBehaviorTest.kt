package com.example.timelinter

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests that verify correct behavior when the phone is unlocked:
 * 1. Current app should be checked immediately
 * 2. Token bucket should be refilled based on time passed while locked
 */
class ScreenUnlockBehaviorTest {

    @Test
    fun tokenBucket_refillsCorrectly_afterLongPeriodWhileLocked() {
        // Simulate: bucket was empty when screen turned off
        val previousRemainingMs = 0L
        val previousAccumulatedMs = 0L
        val previousGoodAppAccumulatedMs = 0L
        
        // 2 hours passed while phone was locked
        val lastUpdateTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        val nowMs = System.currentTimeMillis()
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(30),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(5),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )
        
        // User was NOT on wasteful app (phone was locked, or on home screen after unlock)
        val result = TokenBucket.update(
            previousRemainingMs = previousRemainingMs,
            previousAccumulatedNonWastefulMs = previousAccumulatedMs,
            previousGoodAppAccumulatedMs = previousGoodAppAccumulatedMs,
            lastUpdateTimeMs = lastUpdateTime,
            nowMs = nowMs,
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // After 2 hours of non-wasteful time, bucket should be full
        // 2 hours = 120 minutes
        // Every 10 minutes, add 5 minutes: 120 / 10 * 5 = 60 minutes
        // Max is 30 minutes, so should be capped at 30
        assertEquals(config.maxThresholdMs, result.newRemainingMs)
    }

    @Test
    fun tokenBucket_refillsPartially_afterShortPeriodWhileLocked() {
        // Simulate: bucket had 5 minutes remaining when screen turned off
        val previousRemainingMs = TimeUnit.MINUTES.toMillis(5)
        val previousAccumulatedMs = 0L
        val previousGoodAppAccumulatedMs = 0L
        
        // 20 minutes passed while phone was locked
        val lastUpdateTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        val nowMs = System.currentTimeMillis()
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(30),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(5),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )
        
        // User was NOT on wasteful app
        val result = TokenBucket.update(
            previousRemainingMs = previousRemainingMs,
            previousAccumulatedNonWastefulMs = previousAccumulatedMs,
            previousGoodAppAccumulatedMs = previousGoodAppAccumulatedMs,
            lastUpdateTimeMs = lastUpdateTime,
            nowMs = nowMs,
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // After 20 minutes of non-wasteful time:
        // - 20 minutes elapsed with accumulated = 0
        // - Replenish: 20 / 10 * 5 = 10 minutes added
        // - Remaining: 5 + 10 = 15 minutes
        assertEquals(TimeUnit.MINUTES.toMillis(15), result.newRemainingMs)
    }

    @Test
    fun tokenBucket_handlesUnlockToWastefulApp_correctly() {
        // Simulate: bucket was nearly full when screen turned off
        val previousRemainingMs = TimeUnit.MINUTES.toMillis(28)
        val previousAccumulatedMs = 0L
        val previousGoodAppAccumulatedMs = 0L
        
        // 15 minutes passed while phone was locked
        val lastUpdateTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15)
        val nowMs = System.currentTimeMillis()
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(30),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(5),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )
        
        // First update: unlock with no app (home screen or transitional state)
        val unlockResult = TokenBucket.update(
            previousRemainingMs = previousRemainingMs,
            previousAccumulatedNonWastefulMs = previousAccumulatedMs,
            previousGoodAppAccumulatedMs = previousGoodAppAccumulatedMs,
            lastUpdateTimeMs = lastUpdateTime,
            nowMs = nowMs,
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // After 15 minutes: 15 / 10 * 5 = 7.5 → 7 minutes (truncated)
        // Remaining: 28 + 7 = 35, but accumulator kicks in
        // Actually: 15 min elapsed, 10 min goes to replenish (adds 5), 5 min goes to accumulator
        // So: 28 + 5 = 33 minutes, capped at 30
        assertTrue("Bucket should be at or near full after unlock", 
            unlockResult.newRemainingMs >= TimeUnit.MINUTES.toMillis(28))
        
        // Now user opens a wasteful app immediately after unlock
        val wastefulResult = TokenBucket.update(
            previousRemainingMs = unlockResult.newRemainingMs,
            previousAccumulatedNonWastefulMs = unlockResult.newAccumulatedNonWastefulMs,
            previousGoodAppAccumulatedMs = unlockResult.newGoodAppAccumulatedMs,
            lastUpdateTimeMs = nowMs,
            nowMs = nowMs + 1000, // 1 second later
            isCurrentlyWasteful = true,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // Only 1 second passed, so bucket should decrease by 1 second
        assertTrue("Bucket should decrease when on wasteful app",
            wastefulResult.newRemainingMs < unlockResult.newRemainingMs)
    }

    @Test
    fun tokenBucket_doesNotRefill_ifWastefulAppOpenedImmediately() {
        // Edge case: user unlocks phone directly into a wasteful app
        val previousRemainingMs = TimeUnit.MINUTES.toMillis(10)
        val previousAccumulatedMs = 0L
        val previousGoodAppAccumulatedMs = 0L
        
        // 30 minutes passed while phone was locked
        val lastUpdateTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
        val nowMs = System.currentTimeMillis()
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(30),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(5),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )
        
        // User unlocked directly into wasteful app (e.g., notification opened the app)
        val result = TokenBucket.update(
            previousRemainingMs = previousRemainingMs,
            previousAccumulatedNonWastefulMs = previousAccumulatedMs,
            previousGoodAppAccumulatedMs = previousGoodAppAccumulatedMs,
            lastUpdateTimeMs = lastUpdateTime,
            nowMs = nowMs,
            isCurrentlyWasteful = true,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // 30 minutes passed while wasteful: bucket should decrease by 30 minutes
        // 10 - 30 = -20 minutes (capped at 0)
        assertEquals(0L, result.newRemainingMs)
    }

    @Test
    fun tokenBucket_refillsPlusGoodAppReward_afterUnlock() {
        // Simulate: user had a good app open when they locked the phone
        // Or opens a good app immediately after unlock
        val previousRemainingMs = TimeUnit.MINUTES.toMillis(15)
        val previousAccumulatedMs = 0L
        val previousGoodAppAccumulatedMs = TimeUnit.MINUTES.toMillis(3) // 3 min accumulated before lock
        
        // 10 minutes passed while phone was locked
        val lastUpdateTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)
        val nowMs = System.currentTimeMillis()
        
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(30),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(10),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(5),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )
        
        // After unlock, user opens a good app
        val result = TokenBucket.update(
            previousRemainingMs = previousRemainingMs,
            previousAccumulatedNonWastefulMs = previousAccumulatedMs,
            previousGoodAppAccumulatedMs = previousGoodAppAccumulatedMs,
            lastUpdateTimeMs = lastUpdateTime,
            nowMs = nowMs,
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )
        
        // 10 minutes passed:
        // - Regular refill: 10 / 10 * 5 = 5 minutes
        // - Good app time: 10 + 3 (accumulated) = 13 minutes
        // - Rewards: 13 / 5 * 10 = 26 minutes reward (2 full intervals)
        // - New accumulated: 13 % 5 = 3 minutes
        // Total: 15 + 5 + 20 = 40, but capped at 30 + overfill (60)
        assertTrue("Bucket should increase significantly with good app bonus",
            result.newRemainingMs > previousRemainingMs)
        
        // Should have some good app accumulation remaining
        assertTrue("Good app accumulator should have remainder",
            result.newGoodAppAccumulatedMs < config.goodAppRewardIntervalMs)
    }
}


