package com.timelinter.app

import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendPayloadBuilderTest {

    @Test
    fun `converts history to backend contents preserving roles and text`() {
        val history = listOf(
            Content(role = "user", parts = listOf(TextPart("Hello there"))),
            Content(role = "model", parts = listOf(TextPart("Hi user"))),
        )

        val result = BackendPayloadBuilder.toBackendContents(history)

        assertEquals(2, result.size)
        assertEquals("user", result[0].role)
        assertEquals(listOf("Hello there"), result[0].parts.map { it.text })
        assertEquals("model", result[1].role)
        assertEquals(listOf("Hi user"), result[1].parts.map { it.text })
    }

    @Test
    fun `skips entries without role or text parts`() {
        val history = listOf(
            Content(role = null, parts = listOf(TextPart("ignored"))),
            Content(role = "model", parts = emptyList()),
            Content(role = "user", parts = listOf(TextPart("kept"))),
        )

        val result = BackendPayloadBuilder.toBackendContents(history)

        assertEquals(1, result.size)
        assertEquals("user", result.first().role)
        assertEquals(listOf("kept"), result.first().parts.map { it.text })
    }
}


