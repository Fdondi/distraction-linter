package com.timelinter.app

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentConfig
import com.google.ai.client.generativeai.type.TextPart
import com.timelinter.app.BackendPayloadBuilder
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
    defaultTask: AITask = AITask.FIRST_MESSAGE,
    private val backendGateway: BackendGateway = RealBackendGateway(),
    private val authProvider: suspend (Context) -> String? = { ctx -> AuthManager.signIn(ctx) },
) {
    private val tag = "AIInteractionManager"
    private var generativeModel: GenerativeModel? = null
    private var currentTask: AITask = defaultTask
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val backendAuthHelper = BackendAuthHelper(
        signIn = { authProvider(context) },
        getStoredToken = { ApiKeyManager.getGoogleIdToken(context) },
        saveTokenWithTimestamp = { token, time ->
            ApiKeyManager.saveGoogleIdToken(context, token, time)
        },
        clearToken = { ApiKeyManager.clearGoogleIdToken(context) },
        backend = backendGateway,
        getLastRefreshTimeMs = { ApiKeyManager.getGoogleIdTokenLastRefresh(context) },
        timeProviderMs = { System.currentTimeMillis() },
    )

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
             // Convert history to structured contents
             val contentsPayload = BackendPayloadBuilder
                 .toBackendContents(history)
                 .ifEmpty {
                     val fallbackText = history.joinToString("\n") { content ->
                         content.parts.filterIsInstance<TextPart>().joinToString(" ") { it.text }
                     }
                     listOf(
                         BackendClient.BackendContent(
                             role = "user",
                             parts = listOf(BackendClient.BackendPart(fallbackText))
                         )
                     )
                 }

             val config = AIConfigManager.getModelForTask(context, task)
             val modelId = config.modelName 

             return try {
                 val response = backendAuthHelper.generateWithAutoRefresh(
                     model = modelId,
                     contents = contentsPayload,
                     prompt = null, // avoid legacy prompt duplication
                 )
                 // Parse function calls from backend response
                 val tools = response.function_calls.mapNotNull { fnCall ->
                     parseBackendFunctionCall(fnCall)
                 }
                ParsedResponse(userMessage = response.result, tools = tools, authExpired = false)
             } catch (e: BackendAuthException) {
                 Log.e(tag, "Backend auth error", e)
                ParsedResponse(
                    userMessage = "(Backend Auth Error: ${e.message})",
                    tools = emptyList(),
                    authExpired = true,
                )
             } catch (e: BackendHttpException) {
                 Log.e(tag, "Backend HTTP error", e)
                 return mapBackendHttpError(e)
             } catch (e: Exception) {
                 Log.e(tag, "Backend error", e)
                 ParsedResponse(userMessage = "(Backend Error: ${e.message})", tools = emptyList())
             }

        } else {
             val model = getInitializedModel(task) ?: return ParsedResponse(userMessage = "(Error: AI not initialized)", tools = emptyList())
             return try {
                 // Create config with function declarations for function calling
                 val tool = FunctionDeclarations.createTool()
                 val config = GenerateContentConfig(
                     tools = listOf(tool)
                 )
                 val response = model.generateContent(
                     contents = history,
                     config = config
                 )
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
             
             val config = AIConfigManager.getModelForTask(context, task)
             val modelId = config.modelName

             return try {
                 val structured = BackendPayloadBuilder
                     .toBackendContents(contents)
                     .ifEmpty {
                         val fallback = contents.joinToString("\n") { content ->
                             content.parts.filterIsInstance<TextPart>().joinToString(" ") { it.text }
                         }
                         listOf(
                             BackendClient.BackendContent(
                                 role = "user",
                                 parts = listOf(BackendClient.BackendPart(fallback))
                             )
                         )
                     }
                 val response = backendAuthHelper.generateWithAutoRefresh(
                     model = modelId,
                     contents = structured,
                     prompt = null,
                 )
                 // Parse function calls from backend response
                 val tools = response.function_calls.mapNotNull { fnCall ->
                     parseBackendFunctionCall(fnCall)
                 }
                 ParsedResponse(userMessage = response.result, tools = tools)
            } catch (e: BackendHttpException) {
                Log.e(tag, "Backend HTTP error (custom contents)", e)
                mapBackendHttpError(e)
             } catch (e: Exception) {
                 Log.e(tag, "Backend error (custom contents)", e)
                 null
             }
         } else {
             val model = getInitializedModel(task) ?: return null
             return try {
                 // Create config with function declarations for function calling
                 val tool = FunctionDeclarations.createTool()
                 val config = GenerateContentConfig(
                     tools = listOf(tool)
                 )
                 val response = model.generateContent(
                     contents = contents,
                     config = config
                 )
                 GeminiFunctionCallParser.parse(response)
             } catch (e: Exception) {
                 Log.e(tag, "Direct API error (custom contents)", e)
                 null
             }
         }
    }

    /**
     * Parse a backend function call into a ToolCommand.
     */
    private fun parseBackendFunctionCall(fnCall: BackendClient.FunctionCall): ToolCommand? {
        fun getIntValue(elem: JsonElement?): Int? {
            return elem?.jsonPrimitive?.let { prim ->
                if (prim.isString) prim.content.toIntOrNull()
                else try { prim.int } catch (e: Exception) { null }
            }
        }
        
        fun getStringValue(elem: JsonElement?): String? {
            return elem?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        return when (fnCall.name.lowercase()) {
            "allow" -> {
                val minutes = getIntValue(fnCall.args["minutes"])
                val app = getStringValue(fnCall.args["app"])
                minutes?.takeIf { it > 0 }?.let { ToolCommand.Allow(it.minutes, app) }
            }
            "remember" -> {
                val content = getStringValue(fnCall.args["content"])
                val minutes = getIntValue(fnCall.args["minutes"])
                content?.let { ToolCommand.Remember(it, minutes?.minutes) }
            }
            else -> null
        }
    }

    companion object {
        fun mapBackendHttpError(e: BackendHttpException): ParsedResponse {
            if (e.statusCode == 403) {
                when (e.code) {
                    BackendAccessCode.PENDING_APPROVAL -> {
                        return ParsedResponse(
                            userMessage = "Your account is pending approval. Please wait until it is activated.",
                            tools = emptyList(),
                            authExpired = false,
                        )
                    }
                    BackendAccessCode.ACCESS_REFUSED -> {
                        return ParsedResponse(
                            userMessage = "Access has been refused for this account. Please contact support if you believe this is an error.",
                            tools = emptyList(),
                            authExpired = false,
                        )
                    }
                }
            }
            return ParsedResponse(
                userMessage = "(Backend Error: HTTP ${e.statusCode})",
                tools = emptyList(),
                authExpired = false,
            )
        }

        fun composeUserMessageWithToolErrors(
            baseMessage: String,
            toolErrors: List<ToolCallIssue>
        ): String {
            if (toolErrors.isEmpty()) return baseMessage

            val builder = StringBuilder()
            if (baseMessage.isNotBlank()) {
                builder.append(baseMessage.trim())
                builder.append("\n\n")
            }

            builder.append("Tool call could not be processed:")
            toolErrors.forEach { issue ->
                val reason = when (issue.reason) {
                    ToolCallIssueReason.TEXT_TOOL_FORMAT -> "Tool call was written as text."
                    ToolCallIssueReason.INVALID_ARGS -> "Tool call had invalid arguments."
                    ToolCallIssueReason.UNSUPPORTED_TOOL -> "Unsupported tool name."
                }
                builder.append("\n• ${issue.rawText} — $reason")
            }
            return builder.toString()
        }
    }
}
