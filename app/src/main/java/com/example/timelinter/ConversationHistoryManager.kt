package com.example.timelinter

import android.content.Context
import android.util.Log
import androidx.core.app.Person
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import java.util.Date

class UserConversationHistory(private val coachName: String = "Adam") {
    private val conversation = mutableListOf<ChatMessage>()
    private val userPerson = Person.Builder().setName("You").setKey("user").build()
    private val aiPerson = Person.Builder().setName(coachName).setKey("ai").setBot(true).build()
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
            .replace("{{CURRENT_TIME_AND_DATE}}", Date().toString())
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

    // Adds a model-side note to the API history only (not visible in user UI)
    fun addModelNote(note: String) {
        conversation.add(content(role = "model") { text("[TOOL] $note") })
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
    private val coachName = ApiKeyManager.getCoachName(context)
    private val userConversationHistory = UserConversationHistory(coachName)
    private val apiConversationHistory = APIConversationHistory(
        context, systemPrompt, aiMemoryTemplate, userInfoTemplate, userInteractionTemplate
    )
    
    // Track how many messages we've published to avoid duplication
    private var publishedMessageCount = 0

    init {
        Log.d(TAG, "ConversationHistoryManager initialized with systemPrompt: ${systemPrompt.take(200)}...")
        publishApiHistory()
        // Publish current memory to the log store for UI
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun startNewSession(appName: String, sessionTimeMs: Long = 0L, dailyTimeMs: Long = 0L) {
        Log.i(TAG, "Starting new session for $appName")
        
        // Clear user-visible history (this resets for each session)
        userConversationHistory.clear()
        
        // Add separator to persistent log
        ConversationLogStore.addSessionSeparator(appName)
        
        // Reset published count for new session
        publishedMessageCount = 0
        
        // Initialize API conversation with the 3-step process from interaction.md
        apiConversationHistory.initializeConversation(appName, sessionTimeMs, dailyTimeMs)
        
        // Note: The initial conversation is NOT added to user-visible history
        // as per interaction.md: "This initial conversation will be added to the AI history 
        // and sent every time, but NOT added to the UI visible conversation."
        
        publishApiHistory()
        logHistoriesState("After startNewSession for $appName")
        // Update memory shown in UI (in case it changed)
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun addUserMessage(messageText: String, currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        userConversationHistory.addUserMessage(messageText)
        apiConversationHistory.addUserMessage(messageText, currentAppName, sessionTimeMs, dailyTimeMs)
        publishApiHistory()
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    // Adds a user message ONLY to API history (not shown in user-visible chat)
    fun addApiOnlyUserMessage(messageText: String, currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        apiConversationHistory.addUserMessage(messageText, currentAppName, sessionTimeMs, dailyTimeMs)
        publishApiHistory()
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun addAIMessage(messageText: String) {
        userConversationHistory.addAIMessage(messageText)
        apiConversationHistory.addAIMessage(messageText)
        publishApiHistory()
    }

    // Log tool usage to API history only (hidden from user-visible chat)
    fun addToolLog(tool: ToolCommand) {
        val note = when (tool) {
            is ToolCommand.Allow -> "ALLOW ${tool.minutes}${tool.app?.let { " min for '" + it + "'" } ?: " min"}"
            is ToolCommand.Remember -> "REMEMBER ${tool.durationMinutes?.let { "$it min" } ?: "FOREVER"}: ${tool.content}"
        }
        apiConversationHistory.addModelNote(note)
        publishApiHistory()
    }

    fun addNoResponseMessage(currentAppName: String, sessionTimeMs: Long, dailyTimeMs: Long) {
        // Add "*no response*" to AI conversation only (decorated), not to UI
        apiConversationHistory.addUserMessage("*no response*", currentAppName, sessionTimeMs, dailyTimeMs)
        Log.d(TAG, "Added '*no response*' to API history only")
        publishApiHistory()
    }

    fun getHistoryForAPI(): List<Content> = apiConversationHistory.getHistory()

    fun getUserVisibleHistory(): List<ChatMessage> = userConversationHistory.getHistory()

    fun clearHistories() {
        userConversationHistory.clear()
        apiConversationHistory.clear()
        Log.d(TAG, "Cleared all conversation histories")
        publishApiHistory()
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    private fun logHistoriesState(context: String) {
        Log.d(TAG, "=== $context ===")
        Log.d(TAG, "API History size: ${apiConversationHistory.getHistory().size}")
        Log.d(TAG, "User History size: ${userConversationHistory.getHistory().size}")
        userConversationHistory.logHistory()
        apiConversationHistory.logHistory()
        Log.d(TAG, "=== End $context ===")
    }

    private fun publishApiHistory() {
        // Only publish new messages since last publish to avoid duplication
        val currentHistory = apiConversationHistory.getHistory()
        val newMessages = currentHistory.drop(publishedMessageCount)
        
        if (newMessages.isNotEmpty()) {
            ConversationLogStore.appendToHistory(newMessages)
            publishedMessageCount = currentHistory.size
        }
    }
} 