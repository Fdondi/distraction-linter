package com.timelinter.app

import com.google.ai.client.generativeai.type.Content

/**
 * Common interface for on-device LLM runtimes (MediaPipe, LiteRT-LM).
 *
 * Each implementation handles model loading, conversation formatting, tool-call extraction,
 * and output limiting in its own way, but exposes the same contract to [AIInteractionManager].
 */
interface OnDeviceRuntime {

    /**
     * Generate a response from the given conversation history.
     *
     * @param history The full conversation as Gemini SDK [Content] objects
     *   (system prompt, memory, user turns, model turns).
     * @param maxOutputTokens Approximate cap on output length.
     *   MediaPipe: applied as post-truncation (can't stop GPU mid-generation).
     *   LiteRT-LM: applied via streaming + cancelProcess() (actually saves GPU).
     * @return Parsed response with user-visible message and any tool commands.
     */
    suspend fun generateResponse(
        history: List<Content>,
        maxOutputTokens: Int
    ): ParsedResponse

    /**
     * Release the model from memory.
     */
    suspend fun release()
}

/**
 * Exception thrown when an on-device model is misconfigured or unavailable.
 */
class OnDeviceModelException(message: String) : Exception(message)
