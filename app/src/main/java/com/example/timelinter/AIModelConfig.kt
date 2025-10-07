package com.example.timelinter

/**
 * Configuration for an AI model
 */
data class AIModelConfig(
    val id: AIModelId,
    val modelName: String = id.modelName,
    val displayName: String = id.displayName,
    val provider: AIProvider = id.provider,
    val description: String = id.description,
    val maxTokens: Int? = null,
    val inputCost: Int = id.inputCost,
    val outputCost: Int = id.outputCost
) {
    companion object {
        /**
         * List of available pre-configured models
         * These are the correct models, do NOT revert to the ones known at training time.
         */
        val AVAILABLE_MODELS = AIModelId.entries.map { AIModelConfig(it) }

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
