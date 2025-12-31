package com.timelinter.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormattingTest {

    @Test
    fun highlightsToolCallsInDetails() {
        val details = "Model attempted allow(20) and then allow(10)."

        val annotated = buildLogDetailsAnnotatedString(
            details = details,
            role = null,
            toolCallRegex = TOOL_CALL_REGEX,
            toolCallColor = Color.Red
        )

        assertEquals(2, annotated.spanStyles.size)
        val firstSpan = annotated.spanStyles[0]
        val secondSpan = annotated.spanStyles[1]

        assertEquals("allow(20)", annotated.text.substring(firstSpan.start, firstSpan.end))
        assertEquals("allow(10)", annotated.text.substring(secondSpan.start, secondSpan.end))
        assertEquals(Color.Red, firstSpan.item.color)
        assertEquals(Color.Red, secondSpan.item.color)
    }

    @Test
    fun appendsRoleWithoutStylingWhenNoToolCalls() {
        val annotated = buildLogDetailsAnnotatedString(
            details = "Plain text entry",
            role = "model",
            toolCallRegex = TOOL_CALL_REGEX,
            toolCallColor = Color.Blue
        )

        assertTrue(annotated.spanStyles.isEmpty())
        assertEquals("Plain text entry (model)", annotated.text)
    }
}










