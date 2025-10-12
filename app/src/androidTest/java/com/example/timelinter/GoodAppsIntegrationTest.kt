package com.example.timelinter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for Good Apps integration.
 * Tests the full flow: GoodAppManager, SettingsManager, and TokenBucket integration.
 */
@RunWith(AndroidJUnit4::class)
class GoodAppsIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any existing settings
        GoodAppManager.saveSelectedApps(context, emptySet())
        SettingsManager.setMaxOverfillMinutes(context, 30)
        SettingsManager.setOverfillDecayPerHourMinutes(context, 10)
        SettingsManager.setGoodAppRewardIntervalMinutes(context, 5)
        SettingsManager.setGoodAppRewardAmountMinutes(context, 10)
        SettingsManager.setGoodAppAccumulatedMs(context, 0L)
    }

    @After
    fun tearDown() {
        // Clean up
        GoodAppManager.saveSelectedApps(context, emptySet())
        SettingsManager.setGoodAppAccumulatedMs(context, 0L)
    }

    @Test
    fun testGoodAppManager_SaveAndRetrieve() {
        val testApps = setOf("com.example.app1", "com.example.app2", "com.example.app3")
        
        GoodAppManager.saveSelectedApps(context, testApps)
        val retrieved = GoodAppManager.getSelectedApps(context)
        
        assertEquals(testApps, retrieved)
    }

    @Test
    fun testGoodAppManager_IsGoodApp() {
        val goodApps = setOf("com.example.goodapp")
        GoodAppManager.saveSelectedApps(context, goodApps)
        
        assertTrue(GoodAppManager.isGoodApp(context, "com.example.goodapp"))
        assertFalse(GoodAppManager.isGoodApp(context, "com.example.badapp"))
    }

    @Test
    fun testGoodAppSettings_MaxOverfill() {
        SettingsManager.setMaxOverfillMinutes(context, 45)
        assertEquals(45, SettingsManager.getMaxOverfillMinutes(context))
    }

    @Test
    fun testGoodAppSettings_OverfillDecay() {
        SettingsManager.setOverfillDecayPerHourMinutes(context, 15)
        assertEquals(15, SettingsManager.getOverfillDecayPerHourMinutes(context))
    }

    @Test
    fun testGoodAppSettings_RewardInterval() {
        SettingsManager.setGoodAppRewardIntervalMinutes(context, 10)
        assertEquals(10, SettingsManager.getGoodAppRewardIntervalMinutes(context))
    }

    @Test
    fun testGoodAppSettings_RewardAmount() {
        SettingsManager.setGoodAppRewardAmountMinutes(context, 20)
        assertEquals(20, SettingsManager.getGoodAppRewardAmountMinutes(context))
    }

    @Test
    fun testGoodAppSettings_AccumulatedTime() {
        val timeMs = TimeUnit.MINUTES.toMillis(3)
        SettingsManager.setGoodAppAccumulatedMs(context, timeMs)
        assertEquals(timeMs, SettingsManager.getGoodAppAccumulatedMs(context, 0L))
    }

    @Test
    fun testTokenBucketIntegration_GoodAppReward() {
        // Create config with good app rewards
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(5),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(5),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )

        // Start at full, use good app for 5 minutes
        val result = TokenBucket.update(
            previousRemainingMs = TimeUnit.MINUTES.toMillis(5),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.MINUTES.toMillis(5),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should earn 10 minutes reward: 5 + 10 = 15
        assertEquals(TimeUnit.MINUTES.toMillis(15), result.newRemainingMs)
        assertEquals(0L, result.newGoodAppAccumulatedMs)
    }

    @Test
    fun testTokenBucketIntegration_OverfillDecay() {
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(5),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(5),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )

        // Start overfilled at 15 minutes, wait 1 hour doing nothing
        val result = TokenBucket.update(
            previousRemainingMs = TimeUnit.MINUTES.toMillis(15),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.HOURS.toMillis(1),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Overfill portion (10 min) should decay by 10 min, plus normal replenishment
        // But result should be clamped at max (5 min) since not using good apps
        assertEquals(TimeUnit.MINUTES.toMillis(5), result.newRemainingMs)
    }

    @Test
    fun testTokenBucketIntegration_MaxOverfillLimit() {
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(5),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(5),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )

        // Start near max overfill (30 min), try to earn more
        val result = TokenBucket.update(
            previousRemainingMs = TimeUnit.MINUTES.toMillis(30),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.MINUTES.toMillis(5),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )

        // Should be clamped at max + maxOverfill = 35 minutes
        assertTrue(result.newRemainingMs <= TimeUnit.MINUTES.toMillis(35))
    }

    @Test
    fun testTokenBucketIntegration_WastefulConsumesOverfill() {
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(5),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(5),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )

        // Start overfilled at 15 minutes, use wasteful app for 3 minutes
        val result = TokenBucket.update(
            previousRemainingMs = TimeUnit.MINUTES.toMillis(15),
            previousAccumulatedNonWastefulMs = 0L,
            previousGoodAppAccumulatedMs = 0L,
            lastUpdateTimeMs = 0L,
            nowMs = TimeUnit.MINUTES.toMillis(3),
            isCurrentlyWasteful = true,
            isCurrentlyGoodApp = false,
            config = config
        )

        // Should consume 3 minutes: 15 - 3 = 12
        assertEquals(TimeUnit.MINUTES.toMillis(12), result.newRemainingMs)
    }

    @Test
    fun testFullWorkflow_EarnAndSpend() {
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(5),
            replenishIntervalMs = TimeUnit.MINUTES.toMillis(5),
            replenishAmountMs = TimeUnit.MINUTES.toMillis(1),
            maxOverfillMs = TimeUnit.MINUTES.toMillis(30),
            overfillDecayPerHourMs = TimeUnit.MINUTES.toMillis(10),
            goodAppRewardIntervalMs = TimeUnit.MINUTES.toMillis(5),
            goodAppRewardAmountMs = TimeUnit.MINUTES.toMillis(10)
        )

        // Step 1: Start at 5 minutes
        var remaining = TimeUnit.MINUTES.toMillis(5)
        var accumulated = 0L
        var goodAppAccumulated = 0L
        var lastUpdate = 0L

        // Step 2: Use good app for 10 minutes
        var result = TokenBucket.update(
            previousRemainingMs = remaining,
            previousAccumulatedNonWastefulMs = accumulated,
            previousGoodAppAccumulatedMs = goodAppAccumulated,
            lastUpdateTimeMs = lastUpdate,
            nowMs = TimeUnit.MINUTES.toMillis(10),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = true,
            config = config
        )
        
        // Should earn 2 rewards: 5 + (2 * 10) = 25 minutes
        assertEquals(TimeUnit.MINUTES.toMillis(25), result.newRemainingMs)
        
        remaining = result.newRemainingMs
        accumulated = result.newAccumulatedNonWastefulMs
        goodAppAccumulated = result.newGoodAppAccumulatedMs
        lastUpdate = TimeUnit.MINUTES.toMillis(10)

        // Step 3: Use wasteful app for 10 minutes
        result = TokenBucket.update(
            previousRemainingMs = remaining,
            previousAccumulatedNonWastefulMs = accumulated,
            previousGoodAppAccumulatedMs = goodAppAccumulated,
            lastUpdateTimeMs = lastUpdate,
            nowMs = TimeUnit.MINUTES.toMillis(20),
            isCurrentlyWasteful = true,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // Should consume 10 minutes: 25 - 10 = 15 minutes
        assertEquals(TimeUnit.MINUTES.toMillis(15), result.newRemainingMs)
        
        remaining = result.newRemainingMs
        accumulated = result.newAccumulatedNonWastefulMs
        goodAppAccumulated = result.newGoodAppAccumulatedMs
        lastUpdate = TimeUnit.MINUTES.toMillis(20)

        // Step 4: Wait 30 minutes (decay should happen)
        result = TokenBucket.update(
            previousRemainingMs = remaining,
            previousAccumulatedNonWastefulMs = accumulated,
            previousGoodAppAccumulatedMs = goodAppAccumulated,
            lastUpdateTimeMs = lastUpdate,
            nowMs = TimeUnit.MINUTES.toMillis(50),
            isCurrentlyWasteful = false,
            isCurrentlyGoodApp = false,
            config = config
        )
        
        // Overfill portion (10 min) decays by 5 min (half hour at 10 min/hr)
        // Plus normal replenishment: 30 min / 5 min = 6 replenishments = 6 min
        // Start: 15 min (5 base + 10 overfill)
        // Replenish: +6 min (but capped at base max, so doesn't add to base)
        // Decay: -5 min from overfill
        // Result: 10 min (5 base + 5 overfill)
        assertEquals(TimeUnit.MINUTES.toMillis(10), result.newRemainingMs)
    }

    @Test
    fun testPersistence_GoodAppAccumulator() {
        // Set accumulated time
        val timeMs = TimeUnit.MINUTES.toMillis(3)
        SettingsManager.setGoodAppAccumulatedMs(context, timeMs)
        
        // Retrieve and verify
        val retrieved = SettingsManager.getGoodAppAccumulatedMs(context, 0L)
        assertEquals(timeMs, retrieved)
        
        // Clear
        SettingsManager.setGoodAppAccumulatedMs(context, 0L)
        val cleared = SettingsManager.getGoodAppAccumulatedMs(context, -1L)
        assertEquals(0L, cleared)
    }

    @Test
    fun testDefaults_GoodAppSettings() {
        // Clear preferences to test defaults
        context.getSharedPreferences("timelinter_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        
        // Test default values
        assertEquals(30, SettingsManager.getMaxOverfillMinutes(context))
        assertEquals(10, SettingsManager.getOverfillDecayPerHourMinutes(context))
        assertEquals(5, SettingsManager.getGoodAppRewardIntervalMinutes(context))
        assertEquals(10, SettingsManager.getGoodAppRewardAmountMinutes(context))
    }
}



