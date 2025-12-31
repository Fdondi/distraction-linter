package com.timelinter.app

import com.google.ai.client.generativeai.type.content
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryManagerLogTest {

    @Test
    fun describeContentForLog_includesTextPartsInsteadOfObjectIds() {
        val item = content(role = "user") {
            text("Hello world from the user")
        }

        val summary = describeContentForLog(item)

        assertTrue(summary.contains("Hello world from the user"))
        assertFalse(summary.contains("TextPart@"))
    }

    @Test
    fun describeContentForLog_handlesMultipleTextParts() {
        val item = content(role = "model") {
            text("First line")
            text("Second line")
        }

        val summary = describeContentForLog(item)

        assertTrue(summary.contains("First line"))
        assertTrue(summary.contains("Second line"))
    }
}

















