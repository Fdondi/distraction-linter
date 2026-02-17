package com.timelinter.app

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.type.Content
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device runtime backed by MediaPipe LLM Inference.
 *
 * Converts structured conversation history to Gemma chat-template text,
 * runs inference, parses text-based tool calls, and applies a post-truncation
 * safety net (MediaPipe cannot stop generation mid-stream).
 */
class MediaPipeRuntime(private val context: Context) : OnDeviceRuntime {

    private val tag = "MediaPipeRuntime"

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private val mutex = Mutex()

    override suspend fun generateResponse(
        history: List<Content>,
        maxOutputTokens: Int
    ): ParsedResponse = withContext(Dispatchers.IO) {
        val inference = getOrCreateInference()
        val prompt = ContentToTextConverter.convert(history)
        Log.d(tag, "Prompt (${prompt.length} chars) to MediaPipe model")
        val rawResponse = inference.generateResponse(prompt)
        Log.d(tag, "Response (${rawResponse.length} chars): ${rawResponse.take(200)}")

        val parsed = TextResponseParser.parseAIResponse(rawResponse)

        // Post-truncation safety net: ~4 chars per token is a rough estimate
        val charLimit = maxOutputTokens * 4
        if (parsed.userMessage.length > charLimit) {
            val truncated = parsed.userMessage.take(charLimit).let { text ->
                // Cut at last sentence boundary if possible
                val lastSentenceEnd = text.lastIndexOfAny(charArrayOf('.', '!', '?'))
                if (lastSentenceEnd > charLimit / 2) text.substring(0, lastSentenceEnd + 1)
                else text
            }
            parsed.copy(userMessage = truncated)
        } else {
            parsed
        }
    }

    override suspend fun release() {
        mutex.withLock {
            llmInference?.close()
            llmInference = null
            loadedModelPath = null
            Log.i(tag, "MediaPipe model released")
        }
    }

    private suspend fun getOrCreateInference(): LlmInference = mutex.withLock {
        val configuredPath = SettingsManager.getMediaPipeModelPath(context)
            ?: throw OnDeviceModelException("MediaPipe model path not configured. Set it in AI Configuration.")

        if (llmInference != null && loadedModelPath == configuredPath) {
            return@withLock llmInference!!
        }

        if (llmInference != null) {
            Log.i(tag, "Model path changed, releasing previous model")
            llmInference?.close()
            llmInference = null
        }

        val modelFile = File(configuredPath)
        if (!modelFile.exists()) {
            throw OnDeviceModelException(
                "Model file not found at: $configuredPath. " +
                "Push a .task file to the device (e.g. via adb push) and update the path in AI Configuration."
            )
        }

        Log.i(tag, "Loading MediaPipe model from: $configuredPath")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(configuredPath)
            .setMaxTokens(1024)
            .setMaxTopK(40)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        loadedModelPath = configuredPath
        Log.i(tag, "MediaPipe model loaded successfully")
        llmInference!!
    }
}
