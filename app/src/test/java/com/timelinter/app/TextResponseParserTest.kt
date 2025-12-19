package com.timelinter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextResponseParserTest {

    @Test
    fun flagsTextualToolCallAsIssueAndKeepsMessage() {
        val result = TextResponseParser.parseAIResponse(
            """
            I'll set a reminder for you.
            allow()
            """.trimIndent()
        )

        assertEquals("I'll set a reminder for you.", result.userMessage)
        assertTrue(result.tools.isEmpty())
        assertEquals(1, result.toolErrors.size)

        val issue = result.toolErrors.first()
        assertEquals(ToolCallIssueReason.TEXT_TOOL_FORMAT, issue.reason)
        assertEquals("allow()", issue.rawText)
    }

    @Test
    fun parsesInlineAllowAndRemovesFromMessage() {
        val result = TextResponseParser.parseAIResponse(
            "Congrats! Take a break.allow(5)"
        )

        assertEquals("Congrats! Take a break.", result.userMessage)
        assertEquals(1, result.tools.size)
        val tool = result.tools.first() as ToolCommand.Allow
        assertEquals(5, tool.duration.inWholeMinutes)
    }
}

