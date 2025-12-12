package com.timelinter.app

import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart

object BackendPayloadBuilder {
    fun toBackendContents(history: List<Content>): List<BackendClient.BackendContent> {
        return history.mapNotNull { content ->
            val parts = content.parts
                .filterIsInstance<TextPart>()
                .mapNotNull { textPart ->
                    val text = textPart.text ?: return@mapNotNull null
                    BackendClient.BackendPart(text = text)
                }
            if (parts.isEmpty()) return@mapNotNull null
            val role = content.role ?: return@mapNotNull null
            BackendClient.BackendContent(role = role, parts = parts)
        }
    }
}


