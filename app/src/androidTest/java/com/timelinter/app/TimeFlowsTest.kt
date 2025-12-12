package com.timelinter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class TimeFlowsTest {

    private lateinit var appContext: android.content.Context
    private lateinit var fakeTime: FakeTimeProvider

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        fakeTime = FakeTimeProvider(0L)
        // Default test-friendly timers
        SettingsManager.setObserveTimer(appContext, 1.minutes)
        SettingsManager.setResponseTimer(appContext, 1.minutes)
        SettingsManager.setMaxThreshold(appContext, 1.minutes)
        SettingsManager.setReplenishRateFraction(appContext, 0.1f) // 6 min/hour
        SettingsManager.setThresholdRemaining(appContext, 1.minutes)
        AIMemoryManager.clearAllMemories(appContext)
    }

    @After
    fun tearDown() {
        AIMemoryManager.clearAllMemories(appContext)
    }

    @Test
    fun flow1_thresholdExceeded_triggersConversationStart() {
        SettingsManager.setReplenishRateFraction(appContext, 0f)
        SettingsManager.setThresholdRemaining(appContext, 1.minutes)
        val bucket = TokenBucket(appContext, fakeTime)

        // First tick establishes state (delta = 0)
        bucket.update(AppState.WASTEFUL)
        fakeTime.advanceMinutes(2)
        val remaining = bucket.update(AppState.WASTEFUL)

        assertEquals(Duration.ZERO, remaining)

        val shouldTrigger = TriggerDecider.shouldTrigger(
            isWasteful = true,
            isAllowed = false,
            remainingTime = remaining
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
        manager.applyAllowCommand(ToolCommand.Allow(duration = 5.minutes))
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
        SettingsManager.setResponseTimer(appContext, 1.minutes)
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
        manager.applyAllowCommand(ToolCommand.Allow(duration = 3.minutes, app = "YouTube"))
        assertTrue(manager.isInObservingState())
        assertTrue(manager.isAllowed("YouTube"))
        assertFalse(manager.isAllowed("TikTok"))
        fakeTime.advanceMinutes(4)
        assertFalse(manager.isAllowed("YouTube"))
    }

    @Test
    fun flow5_temporaryMemory_expiresAfterDuration() {
        val note = "Avoid doomscrolling after 10pm"
        AIMemoryManager.addTemporaryMemory(appContext, note, duration = 1.minutes, timeProvider = fakeTime)
        val before = AIMemoryManager.getAllMemories(appContext, timeProvider = fakeTime)
        assertTrue(before.contains(note))
        // Advance beyond expiry
        fakeTime.advanceMinutes(2)
        val after = AIMemoryManager.getAllMemories(appContext, timeProvider = fakeTime)
        assertFalse(after.contains(note))
    }
}



