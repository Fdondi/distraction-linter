package com.example.timelinter

import android.content.Context
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
    private val context: Context,
    private val systemPrompt: String,
    private val aiMemoryTemplate: String,
    private val userInfoTemplate: String,
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

    fun initializeConversation(appName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        conversation.clear()
        
        // Step 1: Add system prompt (undecorated)
        if (systemPrompt.isBlank()) {
            throw IllegalArgumentException("System prompt is blank")
        }
        addSystemMessage(systemPrompt)
        Log.d(TAG, "Added system prompt: $systemPrompt")
        
        // Step 2: Add AI memory (using REMEMBER tool results)
        val memories = AIMemoryManager.getAllMemories(context)
        if (memories.isNotEmpty()) {
            val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
            conversation.add(content(role = "model") { text(memoryMessage) })
            Log.d(TAG, "Added AI memories: ${memories.length} characters")
        } else {
            // Add empty memory template
            val emptyMemoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", "No previous memories.")
            conversation.add(content(role = "model") { text(emptyMemoryMessage) })
            Log.d(TAG, "Added empty AI memory")
        }
        
        // Step 3: Add user status (decorated with app statistics)
        val userNotes = ApiKeyManager.getUserNotes(context)
        val currentUserPrompt = if (userNotes.isNotEmpty()) {
            userNotes
        } else {
            "Currently on $appName"
        }
        
        val userStatusMessage = userInfoTemplate
            .replace("{{FIXED_USER_PROMPT}}", "User is currently using time-wasting apps")
            .replace("{{CURRENT_USER_PROMPT}}", currentUserPrompt)
            .replace("{{AUTOMATED_DATA}}", "")
        
        // Decorate with app statistics like regular user messages
        val decoratedUserStatus = userInteractionTemplate
            .replace("{{CURRENT_TIME_AND_DATE}}", Date().toString())
            .replace("{{APP_NAME}}", appName)
            .replace("{{SESSION_TIME}}", formatDuration(sessionTimeMs))
            .replace("{{DAILY_TIME}}", formatDuration(dailyTimeMs))
            .replace("{{USER_MESSAGE}}", userStatusMessage)
        
        conversation.add(content(role = "user") { text(decoratedUserStatus) })
        Log.d(TAG, "Added decorated user status: $decoratedUserStatus")
        
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
    private val context: Context,
    private val systemPrompt: String,
    private val aiMemoryTemplate: String,
    private val userInfoTemplate: String,
    private val userInteractionTemplate: String
) {
    private val TAG = "ConvHistoryManager"
    private val userConversationHistory = UserConversationHistory()
    private val apiConversationHistory = APIConversationHistory(
        context, systemPrompt, aiMemoryTemplate, userInfoTemplate, userInteractionTemplate
    )

    init {
        Log.d(TAG, "ConversationHistoryManager initialized with systemPrompt: ${systemPrompt.take(200)}...")
    }

    fun startNewSession(appName: String, sessionTimeMs: Long = 0L, dailyTimeMs: Long = 0L) {
        Log.i(TAG, "Starting new session for $appName")
        
        // Clear both histories
        userConversationHistory.clear()
        
        // Initialize API conversation with the 3-step process from interaction.md
        apiConversationHistory.initializeConversation(appName, sessionTimeMs, dailyTimeMs)
        
        // Note: The initial conversation is NOT added to user-visible history
        // as per interaction.md: "This initial conversation will be added to the AI history 
        // and sent every time, but NOT added to the UI visible conversation."
        
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

    fun addNoResponseMessage(currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        // Add "*no response*" to AI conversation only (decorated), not to UI
        apiConversationHistory.addUserMessage("*no response*", currentAppName, sessionTimeMs, dailyTimeMs)
        Log.d(TAG, "Added '*no response*' to API history only")
    }

    fun getHistoryForAPI(): List<Content> = apiConversationHistory.getHistory()

    fun getUserVisibleHistory(): List<ChatMessage> = userConversationHistory.getHistory()

    fun clearHistories() {
        userConversationHistory.clear()
        apiConversationHistory.clear()
        Log.d(TAG, "Cleared all conversation histories")
    }

    private fun logHistoriesState(context: String) {
        Log.d(TAG, "=== $context ===")
        Log.d(TAG, "API History size: ${apiConversationHistory.getHistory().size}")
        Log.d(TAG, "User History size: ${userConversationHistory.getHistory().size}")
        userConversationHistory.logHistory()
        apiConversationHistory.logHistory()
        Log.d(TAG, "=== End $context ===")
    }
} 