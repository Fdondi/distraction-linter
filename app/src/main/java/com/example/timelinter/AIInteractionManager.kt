package com.example.timelinter

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class AIInteractionManager(private val context: Context) {
    private val TAG = "AIInteractionManager"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Gemini Model
    private var generativeModel: GenerativeModel? = null

    // Prompt templates
    private var systemPromptFixed: String? = null
    private var systemPromptVariableTemplate: String? = null
    private var firstAIMessage: String? = null

    init {
        loadPrompts()
        initializeGeminiModel()
    }

    private fun loadPrompts() {
        systemPromptFixed = loadRawResource(R.raw.gemini_fixed_system_prompt)
        if (systemPromptFixed == null) {
            Log.e(TAG, "Failed to load fixed system prompt!")
        }
        systemPromptVariableTemplate = loadRawResource(R.raw.gemini_variable_system_prompt_template)
        if (systemPromptVariableTemplate == null) {
            Log.e(TAG, "Failed to load variable system prompt template!")
        }
        firstAIMessage = loadRawResource(R.raw.gemini_first_ai_message)
        if (firstAIMessage == null) {
            Log.e(TAG, "Failed to load first AI message!")
        }
    }

    private fun loadRawResource(resId: Int): String? {
        return try {
            context.resources.openRawResource(resId).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading raw resource: $resId", e)
            null
        }
    }

    private fun initializeGeminiModel() {
        val apiKey = ApiKeyManager.getKey(context)
        if (!apiKey.isNullOrEmpty()) {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-2.0-flash-lite",
                    apiKey = apiKey,
                    systemInstruction = content(role="system") { text(systemPromptFixed!!) }
                )
                Log.i(TAG, "GenerativeModel initialized successfully with system prompt.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GenerativeModel", e)
                generativeModel = null
            }
        } else {
            Log.w(TAG, "Cannot initialize GenerativeModel: API Key not found.")
            generativeModel = null
        }
    }

    fun getInitialMessage(appName: String): String {
        return firstAIMessage?.replace("{{APP_NAME}}", appName) 
            ?: "Time to take a break from $appName!"
    }

    fun generateSubsequentResponse(
        appName: String,
        sessionTimeMs: Long,
        dailyTimeMs: Long,
        conversationHistory: List<ChatMessage>,
        onResponse: (String) -> Unit
    ) {
        val currentModel = generativeModel ?: run {
            onResponse("(Error: AI not initialized)")
            return
        }

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            
            try {
                // Build the full prompt structure
                val contents = mutableListOf<Content>()
                
                // Add fixed system prompt
                systemPromptFixed?.let { fixed ->
                    contents.add(content(role = "system") { text(fixed) })
                }
                
                // Add variable system prompt
                val formattedSystemVariablePrompt = systemPromptVariableTemplate
                    .replace("{{APP_NAME}}", appName)
                    .replace("{{SESSION_TIME}}", formatDuration(sessionTimeMs))
                    .replace("{{DAILY_TIME}}", formatDuration(dailyTimeMs))
                contents.add(content(role = "system") { text(formattedSystemVariablePrompt) })
                
                // Add first AI message
                firstAIMessage?.let { first ->
                    contents.add(content(role = "model") { text(first) })
                }
                
                // Add user's last message if present
                conversationHistory.lastOrNull()?.let { lastMsg ->
                    if (lastMsg.isUser) {
                        contents.add(content(role = "user") { text(lastMsg.text.toString()) })
                    }
                }
                
                // Generate response based on the full prompt structure
                val response = currentModel.generateContent(*contents.toTypedArray())
                aiResponseText = response.text
                Log.d(TAG, "Raw Gemini Response (subsequent): $aiResponseText")

                if (aiResponseText == null) {
                    Log.w(TAG, "Gemini subsequent response was null.")
                    errorMessage = "(Error getting subsequent response)"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for subsequent message", e)
                errorMessage = "(API Error: ${e.message})"
            }

            // Return the response through callback
            onResponse(aiResponseText ?: errorMessage ?: "(Unknown Error)")
        }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "$hours h ${minutes % 60} min"
            minutes > 0 -> "$minutes min ${seconds % 60} s"
            else -> "$seconds s"
        }
    }
} 