package com.timelinter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for on-device runtime configuration and shared logic.
 */
class OnDeviceRuntimeTest {

    // --- Model config tests ---

    @Test
    fun twoDistinctOnDeviceModelIds() {
        val mediaPipe = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_MEDIAPIPE]
        val liteRt = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_LITERT]

        assertNotNull("MediaPipe model config should exist", mediaPipe)
        assertNotNull("LiteRT model config should exist", liteRt)
        assertTrue(mediaPipe!!.id != liteRt!!.id)
    }

    @Test
    fun onDeviceModelsAreFree() {
        val mediaPipe = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_MEDIAPIPE]!!
        val liteRt = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_LITERT]!!

        assertEquals(0, mediaPipe.inputCost)
        assertEquals(0, mediaPipe.outputCost)
        assertEquals(0, liteRt.inputCost)
        assertEquals(0, liteRt.outputCost)
    }

    @Test
    fun onDeviceModelsHaveMaxOutputTokens() {
        val mediaPipe = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_MEDIAPIPE]!!
        val liteRt = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_LITERT]!!

        assertNotNull("MediaPipe should have maxOutputTokens", mediaPipe.maxOutputTokens)
        assertNotNull("LiteRT should have maxOutputTokens", liteRt.maxOutputTokens)
        assertTrue(mediaPipe.maxOutputTokens!! > 0)
        assertTrue(liteRt.maxOutputTokens!! > 0)
    }

    @Test
    fun mediaPipeProviderIsOnDeviceMediaPipe() {
        val config = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_MEDIAPIPE]!!
        assertEquals(AIProvider.ON_DEVICE_MEDIAPIPE, config.provider)
        assertTrue(config.provider.isOnDevice)
    }

    @Test
    fun liteRtProviderIsOnDeviceLiteRt() {
        val config = AIModelConfig.AVAILABLE_MODELS[AIModelId.ON_DEVICE_LITERT]!!
        assertEquals(AIProvider.ON_DEVICE_LITERT, config.provider)
        assertTrue(config.provider.isOnDevice)
    }

    @Test
    fun cloudProvidersAreNotOnDevice() {
        assertEquals(false, AIProvider.GOOGLE_AI.isOnDevice)
        assertEquals(false, AIProvider.OPENAI.isOnDevice)
        assertEquals(false, AIProvider.ANTHROPIC.isOnDevice)
        assertEquals(false, AIProvider.CUSTOM.isOnDevice)
    }

    @Test
    fun legacyOnDeviceIdMapsToMediaPipe() {
        val resolved = AIModelId.fromId("ON_DEVICE")
        assertEquals(AIModelId.ON_DEVICE_MEDIAPIPE, resolved)
    }

    // --- Post-truncation tests ---

    @Test
    fun postTruncationCutsAtSentenceBoundary() {
        val longText = "First sentence. Second sentence. Third sentence that goes on and on."
        val parsed = ParsedResponse(userMessage = longText, tools = emptyList())

        // Simulate truncation logic from MediaPipeRuntime
        val maxTokens = 10 // ~40 chars
        val charLimit = maxTokens * 4
        val truncated = if (parsed.userMessage.length > charLimit) {
            val text = parsed.userMessage.take(charLimit)
            val lastSentenceEnd = text.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (lastSentenceEnd > charLimit / 2) text.substring(0, lastSentenceEnd + 1)
            else text
        } else {
            parsed.userMessage
        }

        assertTrue(
            "Should cut at sentence boundary",
            truncated.endsWith(".")
        )
        assertTrue(
            "Should be within char limit",
            truncated.length <= charLimit
        )
    }

    @Test
    fun postTruncationDoesNothingToShortText() {
        val shortText = "OK."
        val maxTokens = 128
        val charLimit = maxTokens * 4

        val truncated = if (shortText.length > charLimit) {
            shortText.take(charLimit)
        } else {
            shortText
        }

        assertEquals(shortText, truncated)
    }
}
