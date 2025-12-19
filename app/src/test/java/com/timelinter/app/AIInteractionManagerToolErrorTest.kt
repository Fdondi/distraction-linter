package com.timelinter.app

import org.junit.Assert.assertTrue
import org.junit.Test

class AIInteractionManagerToolErrorTest {

    @Test
    fun appendsToolErrorsToExistingMessage() {
        val issues = listOf(
            ToolCallIssue(
                reason = ToolCallIssueReason.INVALID_ARGS,
                rawText = "allow(\"ten\")"
            )
        )

        val combined = AIInteractionManager.composeUserMessageWithToolErrors(
            baseMessage = "Here is your update.",
            toolErrors = issues
        )

        assertTrue(combined.contains("Here is your update."))
        assertTrue(combined.contains("allow(\"ten\")"))
    }

    @Test
    fun returnsErrorMessageWhenNoBaseMessage() {
        val issues = listOf(
            ToolCallIssue(
                reason = ToolCallIssueReason.TEXT_TOOL_FORMAT,
                rawText = "remember(\"note\" , )"
            )
        )

        val combined = AIInteractionManager.composeUserMessageWithToolErrors(
            baseMessage = "",
            toolErrors = issues
        )

        assertTrue(combined.contains("Tool call could not be processed"))
        assertTrue(combined.contains("remember(\"note\" , )"))
    }
}

