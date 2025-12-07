package com.timelinter.app

/**
 * Represents AI providers/platforms
 */
enum class AIProvider {
    GOOGLE_AI,      // Google AI (Gemini)
    OPENAI,         // OpenAI (GPT)
    ANTHROPIC,      // Anthropic (Claude)
    CUSTOM;         // Custom/Self-hosted

    companion object {
        fun fromString(name: String): AIProvider? {
            return entries.find { it.name == name }
        }
    }
}
