package com.timelinter.app

import com.google.ai.client.generativeai.type.content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentToTextConverterTest {

    private val brevity = ContentToTextConverter.BREVITY_INSTRUCTION

    @Test
    fun convertsSimpleUserModelConversation() {
        val history = listOf(
            content(role = "user") { text("Hello") },
            content(role = "model") { text("Hi there!") },
            content(role = "user") { text("How are you?") }
        )
        val result = ContentToTextConverter.convert(history)

        val expected =
            "<start_of_turn>user\nHello<end_of_turn>\n" +
            "<start_of_turn>model\nHi there!<end_of_turn>\n" +
            "<start_of_turn>user\nHow are you?${brevity}<end_of_turn>\n" +
            "<start_of_turn>model\n"

        assertEquals(expected, result)
    }

    @Test
    fun emptyHistoryProducesOnlyModelTurnStart() {
        val result = ContentToTextConverter.convert(emptyList())
        assertEquals("<start_of_turn>model\n", result)
    }

    @Test
    fun singleUserMessageFormatsCorrectly() {
        val history = listOf(
            content(role = "user") { text("System prompt here") }
        )
        val result = ContentToTextConverter.convert(history)

        val expected =
            "<start_of_turn>user\nSystem prompt here${brevity}<end_of_turn>\n" +
            "<start_of_turn>model\n"

        assertEquals(expected, result)
    }

    @Test
    fun replacesCloudToolInstructionWithTextFormat() {
        val systemPrompt = """Your Role: Act like a friendly coach.

When needed, CALL FUNCTIONS (no textual commands):
- Use function call "allow(minutes, app?)" to grant time.
- Use function call "remember(content, minutes?)" to store memory."""

        val history = listOf(
            content(role = "user") { text(systemPrompt) }
        )
        val result = ContentToTextConverter.convert(history)

        assertFalse(
            "Should not contain cloud-specific instruction",
            result.contains("CALL FUNCTIONS (no textual commands)")
        )
        assertTrue(
            "Should contain on-device text-based instruction",
            result.contains("write tool calls as text in your response")
        )
        // The function descriptions should still be present
        assertTrue(result.contains("allow"))
        assertTrue(result.contains("remember"))
    }

    @Test
    fun preservesMultilineTextContent() {
        val multiline = "Line 1\nLine 2\nLine 3"
        val history = listOf(
            content(role = "user") { text(multiline) }
        )
        val result = ContentToTextConverter.convert(history)

        assertTrue(result.contains("Line 1\nLine 2\nLine 3"))
    }

    @Test
    fun fullConversationWithSystemPromptAndMemory() {
        val history = listOf(
            content(role = "user") { text("You are a coach.") },
            content(role = "model") { text("Previous memories:\nUser likes hiking") },
            content(role = "user") { text("==CONTEXT==\nApp: YouTube\n==USER'S MESSAGE==\nHi") },
            content(role = "model") { text("Hey! I see you're on YouTube.") },
            content(role = "user") { text("==CONTEXT==\nApp: YouTube\n==USER'S MESSAGE==\n*no response*") }
        )
        val result = ContentToTextConverter.convert(history)

        // Should have 5 turns plus the model prompt at the end
        assertEquals(6, result.split("<start_of_turn>").size) // 5 turns + final model
        assertTrue(result.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun brevityInstructionAppendedToLastUserTurn() {
        val history = listOf(
            content(role = "user") { text("System prompt") },
            content(role = "model") { text("OK") },
            content(role = "user") { text("User question") }
        )
        val result = ContentToTextConverter.convert(history)

        // Brevity instruction should appear on the last user turn only
        val turns = result.split("<start_of_turn>").filter { it.isNotEmpty() }

        // First user turn should NOT have brevity
        assertFalse(turns[0].contains("Keep your response"))

        // Last user turn (turns[2]) should have brevity
        assertTrue(turns[2].contains("Keep your response"))
    }

    @Test
    fun brevityInstructionNotOnModelTurns() {
        val history = listOf(
            content(role = "user") { text("Hello") },
            content(role = "model") { text("Hi") }
        )
        val result = ContentToTextConverter.convert(history)

        // The model turn should not contain brevity
        val modelTurnStart = result.indexOf("<start_of_turn>model\nHi")
        val modelTurnEnd = result.indexOf("<end_of_turn>", modelTurnStart)
        val modelTurn = result.substring(modelTurnStart, modelTurnEnd)
        assertFalse(modelTurn.contains("Keep your response"))
    }
}
