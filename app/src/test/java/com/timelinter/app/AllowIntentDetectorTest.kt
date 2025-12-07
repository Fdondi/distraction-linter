package com.timelinter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllowIntentDetectorTest {

    @Test
    fun infer_withExplicitMinutes_detectsAndUsesMinutes() {
        val msg = "Got it — take the time you need, back in 15 minutes."
        val result = AllowIntentDetector.inferAllow(msg, "YouTube")
        assertEquals(15, result?.minutes)
        assertEquals("YouTube", result?.app)
    }

    @Test
    fun infer_withShowerPhrase_defaultsToHeuristic() {
        val msg = "Enjoy your shower, I\'ll be here."
        val result = AllowIntentDetector.inferAllow(msg, "Instagram")
        assertEquals(20, result?.minutes)
        assertEquals("Instagram", result?.app)
    }

    @Test
    fun infer_noIntent_returnsNull() {
        val msg = "Sounds good. How did it go?"
        val result = AllowIntentDetector.inferAllow(msg, null)
        assertNull(result)
    }

    @Test
    fun infer_capsMinutesWithinBounds() {
        val msg = "Take your time, back in 9999 minutes."
        val result = AllowIntentDetector.inferAllow(msg, null)
        assertEquals(240, result?.minutes)
    }
}


