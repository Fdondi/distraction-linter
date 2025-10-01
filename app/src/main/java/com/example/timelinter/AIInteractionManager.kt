package com.example.timelinter

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.example.timelinter.ApiKeyManager

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
    private var generativeModel: GenerativeModel? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        initializeModel()
    }

    private fun initializeModel() {
        val apiKey = ApiKeyManager.getKey(context)
        if (apiKey != null) {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey
                    // Add safetySettings and generationConfig if needed
                )
                Log.i(TAG, "GenerativeModel initialized successfully with model gemini-2.5-flash.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GenerativeModel", e)
                // Consider how to handle this error - e.g., notify user, disable AI features
            }
        } else {
            Log.e(TAG, "API Key not found. GenerativeModel cannot be initialized.")
            // Handle missing API key - e.g., prompt user in UI
        }
    }

    fun getInitializedModel(): GenerativeModel? {
        if (generativeModel == null) {
            Log.w(TAG, "getInitializedModel called but model is null. Attempting re-initialization.")
            initializeModel() // Attempt to re-initialize if null
        }
        return generativeModel
    }

    // Generate a response from explicitly provided contents (bypasses pulling from history)
    fun generateFromContents(
        contents: List<com.google.ai.client.generativeai.type.Content>,
        onResponse: (String) -> Unit
    ) {
        val currentModel = getInitializedModel() ?: run {
            onResponse("(Error: AI not initialized - Model unavailable)")
            return
        }
        serviceScope.launch {
            try {
                val response = currentModel.generateContent(*contents.toTypedArray())
                onResponse(response.text ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API (custom contents)", e)
                onResponse("")
            }
        }
    }

    fun generateSubsequentResponse(
        appName: String,
        sessionTimeMs: Long,
        dailyTimeMs: Long,
        onResponse: (String) -> Unit
    ) {
        val currentModel = getInitializedModel() ?: run {
            Log.e(TAG, "generateSubsequentResponse: GenerativeModel not initialized.")
            onResponse("(Error: AI not ready for subsequent response)")
            return
        }

        // Construct a contextual user message for the API.
        // This message itself should NOT be added to the user-visible chat history.
        // It's a prompt for the AI based on continued usage.
        val contextualPromptForAPI = "User is still on $appName. Session time: ${formatDuration(sessionTimeMs)}, Total daily time: ${formatDuration(dailyTimeMs)}. What should I say next?"

        // Add this contextual prompt to the API history ONLY.
        // TODO: Need a method in ConversationHistoryManager to add to API history without affecting user history.
        // For now, this will also add to user history, which is not ideal.
        conversationHistoryManager.addUserMessage(
            messageText = contextualPromptForAPI, // This will appear in UI, needs fix
            currentAppName = appName, 
            sessionTimeMs = sessionTimeMs, 
            dailyTimeMs = dailyTimeMs
        )

        val apiContents = conversationHistoryManager.getHistoryForAPI()
        if (apiContents.isEmpty()) {
            Log.e(TAG, "generateSubsequentResponse: API history is empty. Cannot make API call.")
            onResponse("(Error: AI history empty for subsequent response)")
            return
        }

        Log.d(TAG, "generateSubsequentResponse: Sending to AI. API History size: ${apiContents.size}")

        serviceScope.launch {
            var aiResponseText: String? = null
            var errorMessage: String? = null

            try {
                val response: GenerateContentResponse = currentModel.generateContent(*apiContents.toTypedArray())
                aiResponseText = response.text
                if (aiResponseText == null) {
                    Log.w(TAG, "Gemini response (subsequent) was null text.")
                    errorMessage = "(AI gave an empty response)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for subsequent response", e)
                errorMessage = "(API Error: ${e.message})"
            }

            val messageToSend = aiResponseText ?: errorMessage ?: "(Unknown error from AI)"
            Log.i(TAG, "Processed AI Response (subsequent): $messageToSend")
            onResponse(messageToSend)
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