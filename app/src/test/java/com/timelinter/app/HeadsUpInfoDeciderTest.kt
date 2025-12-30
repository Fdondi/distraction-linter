package com.timelinter.app

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsUpInfoDeciderTest {

    @Test
    fun doesNotPromptWhenAlreadyShown() {
        val shouldPrompt =
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = true,
                channelImportance = NotificationManager.IMPORTANCE_LOW
            )

        assertFalse(shouldPrompt)
    }

    @Test
    fun doesNotPromptWhenImportanceHighOrAbove() {
        assertFalse(
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = false,
                channelImportance = NotificationManager.IMPORTANCE_HIGH
            )
        )
        assertFalse(
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = false,
                channelImportance = NotificationManager.IMPORTANCE_MAX
            )
        )
    }

    @Test
    fun promptsWhenImportanceTooLowOrMissing() {
        assertTrue(
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = false,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        assertTrue(
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = false,
                channelImportance = NotificationManager.IMPORTANCE_LOW
            )
        )
        assertTrue(
            HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                headsUpInfoAlreadyShown = false,
                channelImportance = null
            )
        )
    }
}



















