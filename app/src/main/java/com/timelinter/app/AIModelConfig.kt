package com.timelinter.app

/**
 * Finite list of IDs (no data attached). Metadata is defined below.
 */
enum class AIModelId {
    GEMINI_25_PRO,
    GEMINI_25_FLASH,
    GEMINI_25_FLASH_LITE,
    GEMINI_20_FLASH,
    GEMINI_20_FLASH_LITE,
    ON_DEVICE_MEDIAPIPE,
    ON_DEVICE_LITERT;

    companion object {
        fun fromId(id: String): AIModelId? {
            // Support legacy "ON_DEVICE" persisted value → map to MediaPipe
            if (id == "ON_DEVICE") return ON_DEVICE_MEDIAPIPE
            return entries.firstOrNull { it.name == id }
        }
    }
}

/**
 * Configuration for an AI model derived from AIModelId
 */
data class AIModelConfig(
    val id: AIModelId,
    val modelName: String,
    val displayName: String,
    val provider: AIProvider,
    val description: String,
    val inputCost: Int,
    val outputCost: Int,
    val maxTokens: Int? = null,
    val maxOutputTokens: Int? = null
) {
    companion object {
        /**
         * List of available pre-configured models
         * These are the correct models, do NOT revert to the ones known at training time.
         */
        val AVAILABLE_MODELS: Map<AIModelId, AIModelConfig> = mapOf(
            AIModelId.GEMINI_25_PRO to AIModelConfig(
                id = AIModelId.GEMINI_25_PRO,
                modelName = "gemini-2.5-pro",
                displayName = "Gemini 2.5 Pro",
                provider = AIProvider.GOOGLE_AI,
                description = "Latest Pro model with improved speed and quality",
                inputCost = 125,
                outputCost = 1000
            ),
            AIModelId.GEMINI_25_FLASH to AIModelConfig(
                id = AIModelId.GEMINI_25_FLASH,
                modelName = "gemini-2.5-flash",
                displayName = "Gemini 2.5 Flash",
                provider = AIProvider.GOOGLE_AI,
                description = "Stable, fast model for quick responses",
                inputCost = 30,
                outputCost = 250
            ),
            AIModelId.GEMINI_25_FLASH_LITE to AIModelConfig(
                id = AIModelId.GEMINI_25_FLASH_LITE,
                modelName = "gemini-2.5-flash-lite",
                displayName = "Gemini 2.5 Flash Lite",
                provider = AIProvider.GOOGLE_AI,
                description = "Smaller, faster model for simple tasks",
                inputCost = 10,
                outputCost = 40
            ),
            AIModelId.GEMINI_20_FLASH to AIModelConfig(
                id = AIModelId.GEMINI_20_FLASH,
                modelName = "gemini-2.0-flash",
                displayName = "Gemini 2.0 Flash",
                provider = AIProvider.GOOGLE_AI,
                description = "Older model, costs as much as new Lite model",
                inputCost = 10,
                outputCost = 40
            ),
            AIModelId.GEMINI_20_FLASH_LITE to AIModelConfig(
                id = AIModelId.GEMINI_20_FLASH_LITE,
                modelName = "gemini-2.0-flash-lite",
                displayName = "Gemini 2.0 Flash Lite",
                provider = AIProvider.GOOGLE_AI,
                description = "Cheapest Gemini",
                inputCost = 8,
                outputCost = 30
            ),
            AIModelId.ON_DEVICE_MEDIAPIPE to AIModelConfig(
                id = AIModelId.ON_DEVICE_MEDIAPIPE,
                modelName = "on_device_mediapipe",
                displayName = "On-Device (MediaPipe)",
                provider = AIProvider.ON_DEVICE_MEDIAPIPE,
                description = "Runs locally via MediaPipe. Free, private, no internet. Requires .task model file.",
                inputCost = 0,
                outputCost = 0,
                maxOutputTokens = 128
            ),
            AIModelId.ON_DEVICE_LITERT to AIModelConfig(
                id = AIModelId.ON_DEVICE_LITERT,
                modelName = "on_device_litert",
                displayName = "On-Device (LiteRT-LM)",
                provider = AIProvider.ON_DEVICE_LITERT,
                description = "Runs locally via LiteRT-LM. Free, private, no internet. Requires .litertlm model file.",
                inputCost = 0,
                outputCost = 0,
                maxOutputTokens = 128
            )
        )

        /**
         * Default modelName per task (single source of truth = modelName)
         */
        val DEFAULT_TASK_MODEL_IDS: Map<AITask, AIModelId> = mapOf(
            AITask.FIRST_MESSAGE to AIModelId.GEMINI_25_PRO,
            AITask.FOLLOWUP_NO_RESPONSE to AIModelId.GEMINI_25_FLASH_LITE,
            AITask.USER_RESPONSE to AIModelId.GEMINI_25_FLASH,
            AITask.SUMMARY to AIModelId.GEMINI_25_PRO
        )
    }
}
