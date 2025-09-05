package com.example.timelinter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerDeciderTest {

    @Test
    fun `does not trigger when not wasteful`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = false, isAllowed = false, remainingMs = 0))
    }

    @Test
    fun `does not trigger when allowed`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = true, remainingMs = 0))
    }

    @Test
    fun `does not trigger when bucket has remaining`() {
        assertFalse(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = false, remainingMs = 1))
    }

    @Test
    fun `triggers only when wasteful, not allowed, and bucket empty`() {
        assertTrue(TriggerDecider.shouldTrigger(isWasteful = true, isAllowed = false, remainingMs = 0))
    }
}


