package com.example.timelinter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TimeFlowsTest {

    private lateinit var appContext: android.content.Context
    private lateinit var fakeTime: FakeTimeProvider

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        fakeTime = FakeTimeProvider(0L)
        // Default test-friendly timers
        SettingsManager.setObserveTimerMinutes(appContext, 1)
        SettingsManager.setResponseTimerMinutes(appContext, 1)
        SettingsManager.setMaxThresholdMinutes(appContext, 1)
        SettingsManager.setReplenishIntervalMinutes(appContext, 10)
        SettingsManager.setReplenishAmountMinutes(appContext, 1)
        AIMemoryManager.clearAllMemories(appContext)
    }

    @After
    fun tearDown() {
        AIMemoryManager.clearAllMemories(appContext)
    }

    @Test
    fun flow1_thresholdExceeded_triggersConversationStart() {
        val config = TokenBucketConfig(
            maxThresholdMs = TimeUnit.MINUTES.toMillis(1),
            replenishIntervalMs = 0,
            replenishAmountMs = 0
        )

        val startRemaining = TimeUnit.MINUTES.toMillis(1)
        val lastUpdate = fakeTime.now()

        // Advance time by 2 minutes while app is wasteful
        fakeTime.advanceMinutes(2)
        val update = TokenBucket.update(
            previousRemainingMs = startRemaining,
            previousAccumulatedNonWastefulMs = 0,
            lastUpdateTimeMs = lastUpdate,
            nowMs = fakeTime.now(),
            isCurrentlyWasteful = true,
            config = config
        )

        assertEquals(0L, update.newRemainingMs)

        val shouldTrigger = TriggerDecider.shouldTrigger(
            isWasteful = true,
            isAllowed = false,
            remainingMs = update.newRemainingMs
        )
        assertTrue(shouldTrigger)

        val manager = InteractionStateManager(appContext, fakeTime)
        // Simulate service deciding to start conversation when shouldTrigger==true
        manager.startConversation()
        assertTrue(manager.isInConversationState())
    }

    @Test
    fun flow2_allowGlobal_resetsAndExpires() {
        val manager = InteractionStateManager(appContext, fakeTime)
        // Start from conversation, then ALLOW tool arrives
        manager.startConversation()
        manager.applyAllowCommand(ToolCommand.Allow(minutes = 5))
        // Resets state
        assertTrue(manager.isInObservingState())
        // Allowed globally
        assertTrue(manager.isAllowed())
        // After expiry
        fakeTime.advanceMinutes(6)
        assertFalse(manager.isAllowed())
    }

    @Test
    fun flow3_waitingForResponse_timesOut() {
        val manager = InteractionStateManager(appContext, fakeTime)
        SettingsManager.setResponseTimerMinutes(appContext, 1)
        manager.startConversation()
        manager.startWaitingForResponse()
        // Not timed out immediately
        assertFalse(manager.isResponseTimedOut())
        // After 1 minute, timed out
        fakeTime.advanceMinutes(1)
        assertTrue(manager.isResponseTimedOut())
        // Service would call continueConversation()
        manager.continueConversation()
        assertTrue(manager.isInConversationState())
    }

    @Test
    fun flow4_allowSpecificApp_appliesOnlyToThatApp_andExpires() {
        val manager = InteractionStateManager(appContext, fakeTime)
        manager.startConversation()
        manager.applyAllowCommand(ToolCommand.Allow(minutes = 3, app = "YouTube"))
        assertTrue(manager.isInObservingState())
        assertTrue(manager.isAllowed("YouTube"))
        assertFalse(manager.isAllowed("TikTok"))
        fakeTime.advanceMinutes(4)
        assertFalse(manager.isAllowed("YouTube"))
    }

    @Test
    fun flow5_temporaryMemory_expiresAfterDuration() {
        val note = "Avoid doomscrolling after 10pm"
        AIMemoryManager.addTemporaryMemory(appContext, note, durationMinutes = 1, timeProvider = fakeTime)
        val before = AIMemoryManager.getAllMemories(appContext, timeProvider = fakeTime)
        assertTrue(before.contains(note))
        // Advance beyond expiry
        fakeTime.advanceMinutes(2)
        val after = AIMemoryManager.getAllMemories(appContext, timeProvider = fakeTime)
        assertFalse(after.contains(note))
    }
}



