package com.example.timelinter

import android.util.Log
import androidx.core.app.Person
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content // Required for content {} builder

// Assuming ChatMessage looks something like this. Adjust if different.
// data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())

class ConversationHistoryManager(
    private val systemPrompt: String,
    private val initialAIMessageTemplate: String, // e.g., "Hi, I see you're using {{APP_NAME}}"
    private val userInteractionTemplate: String // e.g., "Context: {{APP_NAME}}, {{SESSION_TIME}}, {{DAILY_TIME}}\nUser: {{USER_MESSAGE}}"
) {
    private val TAG = "ConvHistoryManager"

    // For interaction with the Gemini API
    // Starts with system prompt (as user), then initial AI (as model), then user, model, ...
    private val apiConversation = mutableListOf<Content>()

    // For display to the user
    // Starts with initial AI message (as model), then user, model, ...
    private val userVisibleConversation = mutableListOf<ChatMessage>()
    private val userPerson = Person.Builder().setName("You").setKey("user").build()
    private val aiPerson = Person.Builder().setName("Time Coach").setKey("ai").setBot(true).build()

    private var appNameForContext: String = ""
    // sessionTimeMsForContext and dailyTimeMsForContext are updated with each user message

    init {
        // Initial state is empty, newSession() will set it up.
        Log.d(TAG, "ConversationHistoryManager initialized.")
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

    fun startNewSession(currentAppName: String) {
        appNameForContext = currentAppName
        apiConversation.clear()
        userVisibleConversation.clear()

        // 1. Prime API conversation with system prompt (as user)
        if (systemPrompt.isNotBlank()) {
            apiConversation.add(content(role = "user") { text(systemPrompt) })
            Log.d(TAG, "API history primed with system prompt as user.")
        } else {
            Log.w(TAG, "System prompt is blank. API history not primed with it.")
        }
        
        // The API's *actual* first response (to the system prompt) would conceptually go here if we waited for it.
        // However, we are *not* waiting. The user-visible conversation starts with a *predefined* AI message.

        // 2. Add the predefined initial AI message to user-visible history
        val actualInitialAIMessage = initialAIMessageTemplate.replace("{{APP_NAME}}", appNameForContext)
        if (actualInitialAIMessage.isNotBlank()) {
            userVisibleConversation.add(ChatMessage(actualInitialAIMessage, System.currentTimeMillis(), aiPerson, false))
            Log.d(TAG, "User history initiated with predefined AI message: '${actualInitialAIMessage.take(70)}...'")
            
            // Also, add this predefined initial AI message to the API conversation history as the first "model" turn
            // This occurs *after* the system prompt (which was role: user)
             if (apiConversation.lastOrNull()?.role == "user") { // Should be true if system prompt was added
                apiConversation.add(content(role = "model") { text(actualInitialAIMessage) })
                Log.d(TAG, "Predefined initial AI message also added to API history as model's first turn.")
            } else {
                 Log.w(TAG, "Could not add predefined initial AI message to API history as model, last API role was not user: ${apiConversation.lastOrNull()?.role}")
                 // This case implies system prompt was blank, so API history is empty. Add it as first.
                 if(apiConversation.isEmpty()){
                    apiConversation.add(content(role = "model") { text(actualInitialAIMessage) })
                    Log.d(TAG, "System prompt was blank. Added predefined initial AI message as API history's first model turn.")
                 }
            }
        } else {
            Log.w(TAG, "Initial AI Message template is blank. User-visible conversation will not start with an AI message from here.")
        }
        logHistoriesState("After startNewSession for $currentAppName")
    }

    fun addUserMessage(messageText: String, currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        appNameForContext = currentAppName // Update context for template

        // Add plain message to user-visible history
        userVisibleConversation.add(ChatMessage(messageText, System.currentTimeMillis(), userPerson, true))

        // Add contextualized message to API history
        val contextualizedUserMessage = userInteractionTemplate
            .replace("{{APP_NAME}}", appNameForContext)
            .replace("{{SESSION_TIME}}", formatDuration(sessionTimeMs))
            .replace("{{DAILY_TIME}}", formatDuration(dailyTimeMs))
            .replace("{{USER_MESSAGE}}", messageText)
        
        if (apiConversation.lastOrNull()?.role == "user") {
            Log.w(TAG, "addUserMessage: Attempting to add consecutive user messages to API history. This indicates an issue with the conversation flow. Last API role: ${apiConversation.lastOrNull()?.role}")
            // To maintain alternation, we might need to skip or handle this.
            // For now, let's add it and let the API potentially complain, to highlight the flow issue.
        }
        apiConversation.add(content(role = "user") { text(contextualizedUserMessage) })
        logHistoriesState("After addUserMessage")
    }

    fun addAIMessage(messageText: String) {
        // Add to user-visible history
        userVisibleConversation.add(ChatMessage(messageText, System.currentTimeMillis(), aiPerson, false))
        
        if (apiConversation.lastOrNull()?.role == "model") {
             Log.w(TAG, "addAIMessage: Attempting to add consecutive model messages to API history. This indicates an issue with the conversation flow. Last API role: ${apiConversation.lastOrNull()?.role}")
        }
        apiConversation.add(content(role = "model") { text(messageText.ifBlank { "(AI provided empty response)" }) })
        logHistoriesState("After addAIMessage")
    }

    fun getHistoryForAPI(): List<Content> {
        // Ensure the API history starts correctly: user (system), model (initial UI), user (reply), model ...
        // Or if no system prompt: model (initial UI), user (reply), model ...
        if (apiConversation.isNotEmpty()) {
            val firstRole = apiConversation.first().role
            if (systemPrompt.isNotBlank()) {
                if (firstRole != "user") {
                    Log.e(TAG, "getHistoryForAPI: CRITICAL! With system prompt, API history should start with USER. Found: $firstRole")
                }
                if (apiConversation.size > 1 && apiConversation[1].role != "model") {
                    Log.e(TAG, "getHistoryForAPI: CRITICAL! After system prompt (user), second API message should be MODEL. Found: ${apiConversation.getOrNull(1)?.role}")
                }
            } else { // No system prompt
                if (firstRole != "model") {
                     Log.e(TAG, "getHistoryForAPI: CRITICAL! Without system prompt, API history should start with MODEL. Found: $firstRole")
                }
            }
        }
        return apiConversation.toList() 
    }

    fun getUserVisibleHistory(): List<ChatMessage> {
        return userVisibleConversation.toList()
    }
    
    fun getInitialAIMessageForUI(currentAppNameIfNewSession: String): String {
        // This function is called by the service to get the first message to show the user.
        // If a new session is truly starting, the service should call startNewSession first.
        // This just formats the template.
        return initialAIMessageTemplate.replace("{{APP_NAME}}", currentAppNameIfNewSession)
    }
    
    fun clearHistories() {
        apiConversation.clear()
        userVisibleConversation.clear()
        Log.d(TAG, "Both API and User-visible conversation histories cleared.")
    }

    private fun logHistoriesState(event: String) {
        Log.d(TAG, "--- Conversation Histories State ($event) ---")
        Log.d(TAG, "User-Visible History (size: ${userVisibleConversation.size}):")
        userVisibleConversation.forEachIndexed { i, cm -> Log.d(TAG, "  UserVisible[$i]: role=${if(cm.isUser)"User" else "AI"}, text='${cm.text.take(70)}...'") }
        Log.d(TAG, "API-Bound History (size: ${apiConversation.size}):")
        apiConversation.forEachIndexed { i, c ->
            val textPreview = c.parts.joinToString { part ->
                if (part is TextPart) {
                    part.text.take(70).replace("\n", " ")
                } else {
                    part.toString().take(70)
                }
            }
            Log.d(TAG, "  API[$i]: role=${c.role}, text='${textPreview}...'")
        }
        Log.d(TAG, "--- End of State ($event) ---")
    }
} 