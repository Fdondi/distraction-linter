package com.example.timelinter

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
AI interaction model:
Initial:  reaching out to the user with formattedFirstAIMessage. No AI yet.
Successive updates: if at the next event (either user replied or timeout expired) the user is still on a time wasting app, then call the AI with: 
SYSTEM: fixedSystemPrompt
AI: formattedFirstAIMessage
USER: the user's template actually includes all the context, for example the current app and current time statustics. Then it inlcudes as USER_MESSAGE the user's response, if present, or "no response" if not.
Then keep updating the conversation this way, one AI and one user message. 
 */

class AIInteractionManager(
    private val context: Context,
    private val conversationHistoryManager: ConversationHistoryManager,
    defaultTask: AITask = AITask.FIRST_MESSAGE
) {
    private val tag = "AIInteractionManager"
    private var generativeModel: GenerativeModel? = null
    private var currentTask: AITask = defaultTask
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        initializeModel(defaultTask)
    }

    private fun initializeModel(task: AITask = currentTask) {
        if (SettingsManager.getAIMode(context) != SettingsManager.AI_MODE_DIRECT) {
            Log.d(tag, "Skipping GenerativeModel initialization (Mode is BACKEND)")
            currentTask = task
            return
        }

        val apiKey = ApiKeyManager.getKey(context)
        if (apiKey != null) {
            try {
                val modelConfig = AIConfigManager.getModelForTask(context, task)
                generativeModel = GenerativeModel(
                    modelName = modelConfig.modelName,
                    apiKey = apiKey
                    // Add safetySettings and generationConfig if needed
                )
                currentTask = task
                Log.i(tag, "GenerativeModel initialized successfully with model ${modelConfig.modelName} for task ${task.displayName}.")
            } catch (e: Exception) {
                Log.e(tag, "Error initializing GenerativeModel", e)
            }
        } else {
            Log.e(tag, "API Key not found. GenerativeModel cannot be initialized.")
        }
    }

    private fun getInitializedModel(task: AITask = currentTask): GenerativeModel? {
        if (SettingsManager.getAIMode(context) != SettingsManager.AI_MODE_DIRECT) {
            return null
        }
        // Re-initialize if task changed or model is null
        if (generativeModel == null || task != currentTask) {
            Log.w(tag, "getInitializedModel called - reinitializing for task ${task.displayName}.")
            initializeModel(task)
        }
        return generativeModel
    }

    /**
     * Switch to a different AI task (and potentially different model)
     */
    fun switchTask(task: AITask) {
        if (task != currentTask) {
            Log.i(tag, "Switching from ${currentTask.displayName} to ${task.displayName}")
            initializeModel(task)
        }
    }

    /**
     * Generate response for the current conversation history.
     * Supports both Direct and Backend modes.
     */
    suspend fun generateResponse(task: AITask): ParsedResponse? {
        val mode = SettingsManager.getAIMode(context)
        val history = conversationHistoryManager.getHistoryForAPI()
        
        if (history.isEmpty()) {
             Log.e(tag, "History is empty")
             return null
        }

        if (mode == SettingsManager.AI_MODE_BACKEND) {
             val token = ApiKeyManager.getGoogleIdToken(context)
             if (token.isNullOrEmpty()) {
                 Log.e(tag, "No Google ID Token found (Backend Mode)")
                 return ParsedResponse(userMessage = "(Error: Not signed in. Please sign in via Settings.)", tools = emptyList())
             }
             
             // Convert history to prompt
             val prompt = history.joinToString("\n") { content -> 
                 val role = if (content.role == "model") "AI" else "User"
                 val text = content.parts.filterIsInstance<TextPart>()
                     .joinToString(" ") { it.text }
                 "$role: $text"
             }

             val config = AIConfigManager.getModelForTask(context, task)
             val modelId = config.modelName 

             return try {
                 val resultText = BackendClient.generate(token, prompt, modelId)
                 ParsedResponse(userMessage = resultText, tools = emptyList())
             } catch (e: Exception) {
                 Log.e(tag, "Backend error", e)
                 ParsedResponse(userMessage = "(Backend Error: ${e.message})", tools = emptyList())
             }

        } else {
             val model = getInitializedModel(task) ?: return ParsedResponse(userMessage = "(Error: AI not initialized)", tools = emptyList())
             return try {
                 val response = model.generateContent(*history.toTypedArray())
                 GeminiFunctionCallParser.parse(response)
             } catch (e: Exception) {
                 Log.e(tag, "Direct API error", e)
                 ParsedResponse(userMessage = "(API Error: ${e.message})", tools = emptyList())
             }
        }
    }

    /**
     * Generate response from custom contents (used for archiving/summary).
     */
    suspend fun generateFromContents(
        contents: List<Content>,
        task: AITask = currentTask
    ): ParsedResponse? {
         val mode = SettingsManager.getAIMode(context)
         if (mode == SettingsManager.AI_MODE_BACKEND) {
             val token = ApiKeyManager.getGoogleIdToken(context)
             if (token.isNullOrEmpty()) return null
             
             val prompt = contents.joinToString("\n") { content -> 
                 val role = if (content.role == "model") "AI" else "User"
                 val text = content.parts.filterIsInstance<TextPart>()
                     .joinToString(" ") { it.text }
                 "$role: $text"
             }
             
             val config = AIConfigManager.getModelForTask(context, task)
             val modelId = config.modelName

             return try {
                 val resultText = BackendClient.generate(token, prompt, modelId)
                 ParsedResponse(userMessage = resultText, tools = emptyList())
             } catch (e: Exception) {
                 Log.e(tag, "Backend error (custom contents)", e)
                 null
             }
         } else {
             val model = getInitializedModel(task) ?: return null
             return try {
                 val response = model.generateContent(*contents.toTypedArray())
                 GeminiFunctionCallParser.parse(response)
             } catch (e: Exception) {
                 Log.e(tag, "Direct API error (custom contents)", e)
                 null
             }
         }
    }
}
