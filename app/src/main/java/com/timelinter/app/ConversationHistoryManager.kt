@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import android.content.Context
import android.util.Log
import androidx.core.app.Person
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content
import java.util.Date
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

private const val PART_PREVIEW_LIMIT = 500

internal fun describeContentForLog(content: Content): String {
    val role = content.role ?: "unknown"
    val parts = try { content.parts } catch (_: Exception) { emptyList() }
    val partDescriptions = parts.map { part ->
        when (part) {
            is TextPart -> {
                val flattened = part.text.replace("\n", "\\n")
                if (flattened.length > PART_PREVIEW_LIMIT) {
                    "${flattened.take(PART_PREVIEW_LIMIT)}…(${flattened.length} chars)"
                } else {
                    flattened
                }
            }
            else -> {
                val simple = part::class.java.simpleName.ifBlank { "UnknownPart" }
                "$simple:${part}"
            }
        }
    }
    val partsSummary = if (partDescriptions.isEmpty()) "no parts" else partDescriptions.joinToString(" | ")
    return "Role=$role; Parts=$partsSummary"
}

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
    private val userInteractionTemplate: String,
    private val timeProvider: TimeProvider
) {
    private val TAG = "APIChatHistoryManager"

    private val conversation = mutableListOf<Content>()

    fun getHistory(): List<Content> = conversation.toList()

    fun addUserMessage(messageText: String, currentAppName: String, sessionTime: Duration, dailyTime: Duration) {
        val contextualizedUserMessage = userInteractionTemplate
            .replace("{{CURRENT_TIME_AND_DATE}}", timeProvider.now().toString())
            .replace("{{APP_NAME}}", currentAppName)
            .replace("{{SESSION_TIME}}", formatDuration(sessionTime))
            .replace("{{DAILY_TIME}}", formatDuration(dailyTime))
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

    fun initializeConversation(appName: String, sessionTime: Duration, dailyTime: Duration) {
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
        
        val categoryManager = AppCategoryConfigManager(context)
        val assignments = categoryManager.getAppAssignments()
        val explanations = categoryManager.getAllExplanations()
        val resolvedAssignments = assignments.mapValues { (pkg, _) -> categoryManager.resolveCategory(pkg) }

        val rewardingEntries = resolvedAssignments.filter { it.value.minutesChangePerMinute?.let { rate -> rate > 0f } == true }
        val drainingEntries = resolvedAssignments.filter { it.value.minutesChangePerMinute?.let { rate -> rate < 0f } == true }

        val goodAppsInfo = buildString {
            if (rewardingEntries.isNotEmpty()) {
                appendLine("Rewarding apps to suggest:")
                rewardingEntries.entries.sortedBy { packageDisplayName(it.key) }.forEach { (pkg, category) ->
                    val name = packageDisplayName(pkg)
                    val explanation = explanations[pkg]
                    append("  - $name [${category.label}]")
                    if (!explanation.isNullOrBlank()) {
                        append(": $explanation")
                    }
                    appendLine()
                }
            }
        }

        val badAppsInfo = buildString {
            if (drainingEntries.isNotEmpty()) {
                appendLine("Why these apps are considered wasteful:")
                drainingEntries.entries.sortedBy { packageDisplayName(it.key) }.forEach { (pkg, category) ->
                    val name = packageDisplayName(pkg)
                    val explanation = explanations[pkg]
                    append("  - $name [${category.label}]")
                    if (!explanation.isNullOrBlank()) {
                        append(": $explanation")
                    }
                    appendLine()
                }
            }
        }
        
        val automatedData = listOf(goodAppsInfo, badAppsInfo)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        
        val userStatusMessage = userInfoTemplate
            .replace("{{FIXED_USER_PROMPT}}", "User is currently using time-wasting apps")
            .replace("{{CURRENT_USER_PROMPT}}", currentUserPrompt)
            .replace("{{AUTOMATED_DATA}}", automatedData)
        
        // Decorate with app statistics like regular user messages
        val decoratedUserStatus = userInteractionTemplate
            .replace("{{CURRENT_TIME_AND_DATE}}", timeProvider.now().toString())
            .replace("{{APP_NAME}}", appName)
            .replace("{{SESSION_TIME}}", formatDuration(sessionTime))
            .replace("{{DAILY_TIME}}", formatDuration(dailyTime))
            .replace("{{USER_MESSAGE}}", userStatusMessage)
        
        conversation.add(content(role = "user") { text(decoratedUserStatus) })
        Log.d(TAG, "Added decorated user status: $decoratedUserStatus")
        
        logHistory()
    }

    private fun packageDisplayName(packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    fun clear() {
        conversation.clear()
    }

    fun logHistory() {
        Log.d(TAG, "API Conversation History (size: ${conversation.size}):")
        conversation.forEachIndexed { i, c -> 
            Log.d(TAG, "API[$i]: ${describeContentForLog(c)}")
        }
    }

    private fun formatDuration(millis: Duration): String {
        val seconds = millis.inWholeSeconds
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
    private val userInteractionTemplate: String,
    private val timeProvider: TimeProvider
) {
    private val TAG = "ConvHistoryManager"
    private val coachName = ApiKeyManager.getCoachName(context)
    private val userConversationHistory = UserConversationHistory(coachName)
    private val apiConversationHistory = APIConversationHistory(
        context, systemPrompt, aiMemoryTemplate, userInfoTemplate, userInteractionTemplate, timeProvider
    )
    
    // Track how many messages we've published to avoid duplication
    private var publishedMessageCount = 0

    init {
        Log.d(TAG, "ConversationHistoryManager initialized with systemPrompt: ${systemPrompt.take(200)}...")
        publishApiHistory()
        // Publish current memory to the log store for UI
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun startNewSession(appName: String, sessionTime: Duration = Duration.ZERO, dailyTime: Duration = Duration.ZERO) {
        Log.i(TAG, "Starting new session for $appName")
        EventLogStore.logSessionStarted(appName)
        
        // Clear user-visible history (this resets for each session)
        userConversationHistory.clear()
        
        // Add separator to persistent log
        ConversationLogStore.addSessionSeparator(appName)
        
        // Reset published count for new session
        publishedMessageCount = 0
        
        // Initialize API conversation with the 3-step process from interaction.md
        apiConversationHistory.initializeConversation(appName, sessionTime, dailyTime)
        
        // Note: The initial conversation is NOT added to user-visible history
        // as per interaction.md: "This initial conversation will be added to the AI history 
        // and sent every time, but NOT added to the UI visible conversation."
        
        publishApiHistory()
        logHistoriesState("After startNewSession for $appName")
        // Update memory shown in UI (in case it changed)
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun addUserMessage(messageText: String, currentAppName: String, sessionTime: Duration, dailyTime: Duration) {
        userConversationHistory.addUserMessage(messageText)
        apiConversationHistory.addUserMessage(messageText, currentAppName, sessionTime, dailyTime)
        EventLogStore.logMessage("user", messageText)
        publishApiHistory()
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    // Adds a user message ONLY to API history (not shown in user-visible chat)
    fun addApiOnlyUserMessage(messageText: String, currentAppName: String, sessionTime: Duration, dailyTime: Duration) {
        apiConversationHistory.addUserMessage(messageText, currentAppName, sessionTime, dailyTime)
        publishApiHistory()
        ConversationLogStore.setMemory(AIMemoryManager.getAllMemories(context))
    }

    fun addAIMessage(messageText: String) {
        userConversationHistory.addAIMessage(messageText)
        apiConversationHistory.addAIMessage(messageText)
        EventLogStore.logMessage("model", messageText)
        publishApiHistory()
    }

    // Log tool usage to API history only (hidden from user-visible chat)
    fun addToolLog(tool: ToolCommand) {
        val note = when (tool) {
            is ToolCommand.Allow -> "🔧 TOOL CALLED: allow(${tool.duration}${tool.app?.let { ", \"$it\"" } ?: ""}) - This tool call was made alongside the previous model response"
            is ToolCommand.Remember -> "🔧 TOOL CALLED: remember(\"${tool.content}\"${tool.duration?.let { ", $it" } ?: ""}) - This tool call was made alongside the previous model response"
        }
        apiConversationHistory.addModelNote(note)
        EventLogStore.logTool(tool)
        publishApiHistory()
    }

    fun addToolParseIssue(issue: ToolCallIssue) {
        val note = "⚠️ TOOL CALL FAILED: ${issue.rawText} (${issue.reason})"
        apiConversationHistory.addModelNote(note)
        EventLogStore.logToolParseFailure(issue)
        publishApiHistory()
    }

    fun addNoResponseMessage(currentAppName: String, sessionTime: Duration, dailyTime: Duration) {
        // Add "*no response*" to AI conversation only (decorated), not to UI
        apiConversationHistory.addUserMessage("*no response*", currentAppName, sessionTime, dailyTime)
        Log.d(TAG, "Added '*no response*' to API history only")
        publishApiHistory()
    }

    fun getHistoryForAPI(): List<Content> = apiConversationHistory.getHistory()

    fun getUserVisibleHistory(): List<ChatMessage> = userConversationHistory.getHistory()

    fun clearHistories() {
        userConversationHistory.clear()
        apiConversationHistory.clear()
        Log.d(TAG, "Cleared all conversation histories")
        EventLogStore.logSessionReset("Conversation cleared")
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
