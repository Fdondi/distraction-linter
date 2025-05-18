package com.example.timelinter

import android.util.Log
import androidx.core.app.Person
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import java.util.Date

class UserConversationHistory {
    private val conversation = mutableListOf<ChatMessage>()
    private val userPerson = Person.Builder().setName("You").setKey("user").build()
    private val aiPerson = Person.Builder().setName("Time Coach").setKey("ai").setBot(true).build()
    private val TAG = "UserConvHistoryManager"

    fun addUserMessage(messageText: String) {
        conversation.add(ChatMessage(
            text = messageText,
            isUser = true,
            sender = userPerson
        ))
    }

    fun addAIMessage(messageText: String) {
        conversation.add(ChatMessage(
            text = messageText,
            isUser = false,
            sender = aiPerson
        ))
    }

    fun getHistory(): List<ChatMessage> = conversation.toList()

    fun getHistoryForUI(): List<ChatMessage> = conversation.toList()

    fun clear() {
        conversation.clear()
    }

    fun logHistory() {
        Log.d(TAG, "User-Visible History (size: ${conversation.size}):")
        conversation.forEachIndexed { i, cm -> 
            Log.d(TAG, """
                UserVisible[$i]:
                - Role: ${if(cm.isUser) "User" else "AI"}
                - Timestamp: ${Date(cm.timestamp)}
                - Text: ${cm.text}
            """.trimIndent())
        }
    }
}

class APIConversationHistory(
    private val systemPrompt: String,
    private val initialAIMessageTemplate: String,
    private val userInteractionTemplate: String
) {
    private val TAG = "APIChatHistoryManager"

    private val conversation = mutableListOf<Content>()

    fun getHistory(): List<Content> = conversation.toList()

    fun addUserMessage(messageText: String, currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        val contextualizedUserMessage = userInteractionTemplate
            .replace("{{APP_NAME}}", currentAppName)
            .replace("{{SESSION_TIME}}", formatDuration(sessionTimeMs))
            .replace("{{DAILY_TIME}}", formatDuration(dailyTimeMs))
            .replace("{{USER_MESSAGE}}", messageText)
        
        conversation.add(content(role = "user") { text(contextualizedUserMessage) })
        logHistory()
    }

    fun addAIMessage(messageText: String) {
        conversation.add(content(role = "model") { text(messageText) })
        logHistory()
    }

    fun addSystemMessage(messageText: String) {
        conversation.add(content(role = "user") { text(messageText) })
        logHistory()
    }

    fun clear() {
        conversation.clear()
    }

    fun logHistory() {
        Log.d(TAG, "API Conversation History (size: ${conversation.size}):")
        conversation.forEachIndexed { i, c -> 
            Log.d(TAG, """
                API[$i]:
                - Role: ${c.role}
                - Content: ${c.parts.firstOrNull()?: "empty"}
            """.trimIndent())
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

class ConversationHistoryManager(
    private val systemPrompt: String,
    private val initialAIMessageTemplate: String,
    private val userInteractionTemplate: String
) {
    private val TAG = "ConvHistoryManager"
    private val userConversationHistory = UserConversationHistory()
    private val apiConversationHistory = APIConversationHistory(systemPrompt, initialAIMessageTemplate, userInteractionTemplate)

    init {
        Log.d(TAG, "ConversationHistoryManager initialized with systemPrompt: ${systemPrompt.take(200)}...")
    }

    private fun generateInitialAIMessageForSession(appName: String): String {
        return initialAIMessageTemplate.replace("{{APP_NAME}}", appName)
    }

    fun startNewSession(appName: String) {
        apiConversationHistory.clear()
        userConversationHistory.clear()

        if (systemPrompt.isBlank()) {
            throw IllegalArgumentException("System prompt is blank. API history impossible")
        }
        apiConversationHistory.addSystemMessage(systemPrompt)
        
        val actualInitialAIMessage = generateInitialAIMessageForSession(appName)

        if (actualInitialAIMessage.isBlank()) {
            throw IllegalArgumentException("Initial AI message could not be generated (template likely blank or appName missing). User-visible conversation impossible")
        }
        userConversationHistory.addAIMessage(actualInitialAIMessage)
        apiConversationHistory.addAIMessage(actualInitialAIMessage)
        
        logHistoriesState("After startNewSession for $appName")
    }

    fun addUserMessage(messageText: String, currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        userConversationHistory.addUserMessage(messageText)
        apiConversationHistory.addUserMessage(messageText, currentAppName, sessionTimeMs, dailyTimeMs)
    }

    fun addAIMessage(messageText: String) {
        userConversationHistory.addAIMessage(messageText)
        apiConversationHistory.addAIMessage(messageText)
    }

    fun getHistoryForAPI(): List<Content> = apiConversationHistory.getHistory()

    fun getUserVisibleHistory(): List<ChatMessage> = userConversationHistory.getHistoryForUI()
    
    fun clearHistories() {
        apiConversationHistory.clear()
        userConversationHistory.clear()
        Log.d(TAG, "Both API and User-visible conversation histories cleared.")
    }

    private fun logHistoriesState(event: String) {
        Log.d(TAG, "--- Conversation Histories State ($event) ---")
        userConversationHistory.logHistory()
        apiConversationHistory.logHistory()
        Log.d(TAG, "--- End of State ($event) ---")
    }
} 