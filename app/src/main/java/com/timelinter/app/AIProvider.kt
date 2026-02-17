package com.timelinter.app

/**
 * Represents AI providers/platforms
 */
enum class AIProvider {
    GOOGLE_AI,      // Google AI (Gemini)
    ON_DEVICE_MEDIAPIPE, // On-device inference via MediaPipe LLM Inference
    ON_DEVICE_LITERT,    // On-device inference via LiteRT-LM
    OPENAI,         // OpenAI (GPT)
    ANTHROPIC,      // Anthropic (Claude)
    CUSTOM;         // Custom/Self-hosted

    val isOnDevice: Boolean
        get() = this == ON_DEVICE_MEDIAPIPE || this == ON_DEVICE_LITERT

    companion object {
        fun fromString(name: String): AIProvider? {
            return entries.find { it.name == name }
        }
    }
}
