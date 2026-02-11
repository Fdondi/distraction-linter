package com.timelinter.app

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages on-device LLM inference via MediaPipe's LlmInference API.
 *
 * The model is lazily loaded on first use. If the configured model path changes,
 * the instance is released and re-created on the next call.
 */
class OnDeviceInferenceManager(private val context: Context) {
    private val tag = "OnDeviceInference"

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private val mutex = Mutex()

    /**
     * Generate a text response from a plain-text prompt.
     *
     * @param prompt The full conversation formatted as text (e.g. Gemma chat template).
     * @return The model's text response, or null on error.
     * @throws OnDeviceModelException if the model is not configured or cannot be loaded.
     */
    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = getOrCreateInference()
        Log.d(tag, "Sending prompt (${prompt.length} chars) to on-device model")
        val result = inference.generateResponse(prompt)
        Log.d(tag, "On-device response (${result.length} chars): ${result.take(200)}")
        result
    }

    /**
     * Release the model from memory. Call when the model is no longer needed
     * or when the model path has changed.
     */
    suspend fun release() = mutex.withLock {
        llmInference?.close()
        llmInference = null
        loadedModelPath = null
        Log.i(tag, "On-device model released")
    }

    private suspend fun getOrCreateInference(): LlmInference = mutex.withLock {
        val configuredPath = SettingsManager.getOnDeviceModelPath(context)
            ?: throw OnDeviceModelException("On-device model path not configured. Set it in AI Configuration.")

        // Re-create if path changed
        if (llmInference != null && loadedModelPath == configuredPath) {
            return@withLock llmInference!!
        }

        // Release previous instance if path changed
        if (llmInference != null) {
            Log.i(tag, "Model path changed, releasing previous model")
            llmInference?.close()
            llmInference = null
        }

        val modelFile = File(configuredPath)
        if (!modelFile.exists()) {
            throw OnDeviceModelException(
                "Model file not found at: $configuredPath. " +
                "Push a model file to the device (e.g. via adb push) and update the path in AI Configuration."
            )
        }

        Log.i(tag, "Loading on-device model from: $configuredPath")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(configuredPath)
            .setMaxTokens(1024)
            .setMaxTopK(40)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        loadedModelPath = configuredPath
        Log.i(tag, "On-device model loaded successfully")
        llmInference!!
    }
}

/**
 * Exception thrown when on-device model is misconfigured or unavailable.
 */
class OnDeviceModelException(message: String) : Exception(message)
