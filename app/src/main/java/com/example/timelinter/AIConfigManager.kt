package com.example.timelinter

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Manages AI model configuration per task
 */
object AIConfigManager {
    private const val TAG = "AIConfigManager"
    private const val PREFS_NAME = "ai_config"
    private const val KEY_PREFIX = "task_config_"

    /**
     * Get the configured model for a specific task
     */
    fun getModelForTask(context: Context, task: AITask): AIModelConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + task.name
        val json = prefs.getString(key, null)

        return if (json != null) {
            try {
                val parsed = JSONObject(json)
                // If only modelName is stored, resolve full config from AVAILABLE_MODELS
                if (parsed.has("modelId")) {
                    val id = AIModelId.fromId(parsed.getString("modelId"))
                    id?.let { AIModelConfig.AVAILABLE_MODELS.getValue(it) } ?: AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
                } else {
                    parseModelConfig(parsed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing model config for task $task", e)
                resolveDefault(task)
            }
        } else {
            // Return default for this task
            resolveDefault(task)
        }
    }

    /**
     * Set the model configuration for a specific task
     */
    fun setModelForTask(context: Context, task: AITask, config: AIModelConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + task.name
        // Persist storing only modelName (single source of truth)
        val json = JSONObject().put("modelId", config.id.name)
        prefs.edit().putString(key, json.toString()).apply()
        Log.d(TAG, "Set model for task $task: ${config.modelName}")
    }

    /**
     * Get all task configurations
     */
    fun getAllTaskConfigurations(context: Context): Map<AITask, AIModelConfig> {
        val configs = mutableMapOf<AITask, AIModelConfig>()
        
        AITask.entries.forEach { task ->
            configs[task] = getModelForTask(context, task)
        }
        
        return configs
    }

    /**
     * Get list of available models
     */
    fun getAvailableModels(): List<AIModelConfig> = AIModelConfig.AVAILABLE_MODELS.values.toList()

    /**
     * Reset all configurations to defaults
     */
    fun resetToDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.d(TAG, "Reset all AI configurations to defaults")
    }

    /**
     * Export current configuration to JSON string
     */
    fun exportConfiguration(context: Context): String {
        val rootJson = JSONObject()
        
        AITask.entries.forEach { task ->
            val config = getModelForTask(context, task)
            // Export minimal schema: modelName only
            rootJson.put(task.name, JSONObject().put("modelId", config.id.name))
        }
        
        return rootJson.toString(2) // Pretty print with 2-space indent
    }

    /**
     * Import configuration from JSON string
     * @return true if successful, false otherwise
     */
    fun importConfiguration(context: Context, json: String): Boolean {
        try {
            val rootJson = JSONObject(json)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            AITask.entries.forEach { task ->
                if (rootJson.has(task.name)) {
                    val taskJson = rootJson.getJSONObject(task.name)
                    val modelId = taskJson.optString("modelId", "")
                    if (modelId.isNotBlank() && AIModelId.fromId(modelId) != null) {
                        val key = KEY_PREFIX + task.name
                        editor.putString(key, JSONObject().put("modelId", modelId).toString())
                    }
                }
            }

            editor.apply()
            Log.d(TAG, "Successfully imported AI configuration")
            return true
        } catch (e: JSONException) {
            Log.e(TAG, "Error importing configuration", e)
            return false
        }
    }

    /**
     * Convert AIModelConfig to JSON
     */
    private fun modelConfigToJson(config: AIModelConfig): JSONObject = JSONObject().put("modelId", config.id.name)

    /**
     * Parse AIModelConfig from JSON
     */
    private fun parseModelConfig(json: JSONObject): AIModelConfig {
        val id = if (json.has("modelId")) AIModelId.fromId(json.getString("modelId")) else null
        return id?.let { AIModelConfig.AVAILABLE_MODELS.getValue(it) } ?: AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
    }

    private fun resolveDefault(task: AITask): AIModelConfig {
        val defaultId = AIModelConfig.DEFAULT_TASK_MODEL_IDS[task]
        return defaultId?.let { AIModelConfig.AVAILABLE_MODELS.getValue(it) } ?: AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
    }
}
