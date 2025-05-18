package com.example.timelinter

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    private val conversationHistoryManager: ConversationHistoryManager
) {
    private val TAG = "AIInteractionManager"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var generativeModel: GenerativeModel? = null

    init {
        initializeGeminiModel()
    }

    private fun initializeGeminiModel(): GenerativeModel? {
        val apiKey = ApiKeyManager.getKey(context)
        if (!apiKey.isNullOrEmpty()) {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash-latest",
                    apiKey = apiKey
                )
                Log.i(TAG, "GenerativeModel initialized successfully with model gemini-1.5-flash-latest.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GenerativeModel", e)
                generativeModel = null
            }
        } else {
            Log.w(TAG, "Cannot initialize GenerativeModel: API Key not found.")
            generativeModel = null
        }
        return generativeModel
    }

    fun getInitializedModel(): GenerativeModel? {
        if (generativeModel == null) {
            Log.i(TAG, "GenerativeModel was null, attempting re-initialization.")
            initializeGeminiModel()
        }
        return generativeModel
    }

    fun getInitialMessage(appName: String): String {
        return conversationHistoryManager.getInitialAIMessageForUI(appName)
    }

    fun generateSubsequentResponse(
        appName: String,
        sessionTimeMs: Long,
        dailyTimeMs: Long,
        conversationHistory: List<ChatMessage>,
        onResponse: (String) -> Unit
    ) {
        val currentModel = getInitializedModel() ?: run {
            onResponse("(Error: AI not initialized - Model unavailable)")
            return
        }

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            
            // Add the user's context to the conversation history
            conversationHistoryManager.addUserMessage(
                messageText = "no response",
                currentAppName = appName,
                sessionTimeMs = sessionTimeMs,
                dailyTimeMs = dailyTimeMs
            )

            val apiContents = conversationHistoryManager.getHistoryForAPI()

            if (apiContents.isEmpty()) {
                Log.e(TAG, "generateSubsequentResponse: API history is empty. Cannot make API call.")
                onResponse("(Error: AI Interaction - No content to send)")
                return@launch
            }

            try {
                val response = currentModel.generateContent(*apiContents.toTypedArray())
                aiResponseText = response.text
                Log.d(TAG, "Raw Gemini Response: $aiResponseText")

                if (aiResponseText == null) {
                    Log.w(TAG, "Gemini response was null.")
                    errorMessage = "(Error getting AI response: Null text)"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API", e)
                errorMessage = "(API Error: ${e.message})"
            }

            val result = aiResponseText ?: errorMessage ?: "(Unknown Error obtaining AI response)"
            onResponse(result)
        }
    }

    fun generateResponse(
        onResponse: (String) -> Unit
    ) {
        val currentModel = getInitializedModel() ?: run {
            onResponse("(Error: AI not initialized - Model unavailable)")
            return
        }

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null
            
            val apiContents = conversationHistoryManager.getHistoryForAPI()

            if (apiContents.isEmpty()) {
                Log.e(TAG, "generateResponse: API history from ConversationHistoryManager is empty. Cannot make API call.")
                onResponse("(Error: AI Interaction - No content to send)")
                return@launch
            }
            
            Log.d(TAG, "--- AIInteractionManager: Sending to Gemini (History from Manager) ---")
            apiContents.forEachIndexed { idx, content ->
                 val textPreview = content.parts.joinToString { part ->
                    if (part is com.google.ai.client.generativeai.type.TextPart) {
                        part.text.take(70).replace("\n", " ")
                    } else {
                        part.toString().take(70)
                    }
                }.let { if (it.length > 70) it.substring(0, 70) + "..." else it }
                Log.d(TAG, "APIContent[$idx]: role=${content.role}, text='$textPreview'")
            }
            Log.d(TAG, "--- End of AIInteractionManager: API Request Contents ---")

            try {
                val response = currentModel.generateContent(*apiContents.toTypedArray())
                aiResponseText = response.text
                Log.d(TAG, "Raw Gemini Response: $aiResponseText")

                if (aiResponseText == null) {
                    Log.w(TAG, "Gemini response was null.")
                    errorMessage = "(Error getting AI response: Null text)"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API", e)
                errorMessage = "(API Error: ${e.message})"
            }

            val result = aiResponseText ?: errorMessage ?: "(Unknown Error obtaining AI response)"
            onResponse(result)
        }
    }
} 