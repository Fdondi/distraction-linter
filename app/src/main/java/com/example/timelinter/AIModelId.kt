package com.example.timelinter

/**
 * Canonical list of supported AI models.
 * The enum name (id) is the single source of truth stored in preferences.
 * Cost in cents per 1M tokens.
 */
enum class AIModelId(
    val modelName: String,
    val displayName: String,
    val provider: AIProvider,
    val description: String,
    val inputCost: Int,
    val outputCost: Int,

) {
    GEMINI_25_PRO(
        modelName = "gemini-2.5-pro",
        displayName = "Gemini 2.5 Pro",
        provider = AIProvider.GOOGLE_AI,
        description = "Latest Pro model with improved speed and quality",
        inputCost = 125,
        outputCost = 1000,
    ),
    GEMINI_25_FLASH(
        modelName = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
        provider = AIProvider.GOOGLE_AI,
        description = "Stable, fast model for quick responses",
        inputCost = 30,
        outputCost = 250,
    ),
    GEMINI_25_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        displayName = "Gemini 2.5 Flash Lite",
        provider = AIProvider.GOOGLE_AI,
        description = "Smaller, faster model for simple tasks",
        inputCost = 10,
        outputCost = 40,
    ),
    GEMINI_20_FLASH(
        modelName = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash",
        provider = AIProvider.GOOGLE_AI,
        description = "Older model, costs as much as new Lite model",
        inputCost = 10,
        outputCost = 40,
    ),
    GEMINI_20_FLASH_LITE(
        modelName = "gemini-2.0-flash-lite",
        displayName = "Gemini 2.0 Flash Lite",
        provider = AIProvider.GOOGLE_AI,
        description = "Cheapest Gemini",
        inputCost = 8,
        outputCost = 30,
    );

    companion object {
        fun fromModelName(modelName: String): AIModelId? = entries.firstOrNull { it.modelName == modelName }
        fun fromId(id: String): AIModelId? = entries.firstOrNull { it.name == id }
    }
}


