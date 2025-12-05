package com.example.timelinter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class TriggerDeciderTest {

    @Test
    fun `does not trigger when not wasteful`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = false, isAllowed = false, remainingTime = Duration.ZERO))
    }

    @Test
    fun `does not trigger when allowed`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = true, remainingTime = Duration.ZERO))
    }

    @Test
    fun `does not trigger when bucket has remaining`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = false, remainingTime = 1.minutes))
    }

    @Test
    fun `triggers only when wasteful, not allowed, and bucket empty`() {
        assertTrue(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = false, remainingTime = Duration.ZERO))
    }
}



