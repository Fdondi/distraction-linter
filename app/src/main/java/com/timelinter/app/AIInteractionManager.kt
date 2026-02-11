package com.timelinter.app

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.timelinter.app.BackendPayloadBuilder
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
    private val onDeviceInferenceManager: OnDeviceInferenceManager = OnDeviceInferenceManager(context),
) {
    private val tag = "AIInteractionManager"
    private var generativeModel: GenerativeModel? = null
    private var currentTask: AITask = defaultTask
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val backendAuthHelper = BackendAuthHelper(
        signIn = { authProvider(context) },
        getAppToken = { ApiKeyManager.getAppToken(context) },
        saveAppToken = { token, expiresAtMs ->
            ApiKeyManager.saveAppToken(context, token, expiresAtMs)
        },
        clearAppToken = { ApiKeyManager.clearAppToken(context) },
        getGoogleIdToken = { ApiKeyManager.getGoogleIdToken(context) },
        backend = backendGateway,
        checkAuthStatus = { token ->
            withContext(Dispatchers.IO) {
                BackendClient.checkAuthStatus(token)
            }
        }
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
                val tool = FunctionDeclarations.createTool()
                generativeModel = GenerativeModel(
                    modelName = modelConfig.modelName,
                    apiKey = apiKey,
                    tools = listOf(tool)
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
     * Supports Backend, Direct, and On-Device modes.
     * On-Device mode is selected per-task based on the model's provider.
     */
    suspend fun generateResponse(task: AITask): ParsedResponse? {
        val mode = SettingsManager.getAIMode(context)
        val history = conversationHistoryManager.getHistoryForAPI()
        
        if (history.isEmpty()) {
             Log.e(tag, "History is empty")
             return null
        }

        // Check if this task is configured to use an on-device model
        val modelConfig = AIConfigManager.getModelForTask(context, task)
        if (modelConfig.provider == AIProvider.ON_DEVICE) {
            return generateOnDevice(history, task)
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
                Log.d(tag, "Received ${response.function_calls.size} function calls from backend")
                val tools = response.function_calls.mapNotNull { fnCall ->
                    val parsed = parseBackendFunctionCall(fnCall)
                    if (parsed == null) {
                        Log.w(tag, "Failed to parse function call: ${fnCall.name} with args ${fnCall.args}")
                    }
                    parsed
                }
                Log.d(tag, "Successfully parsed ${tools.size} tools from ${response.function_calls.size} function calls")
                ParsedResponse(userMessage = response.result ?: "", tools = tools, authExpired = false)
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
                 // Tools are configured on the model, just pass contents
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
         // Check if this task is configured to use an on-device model
         val modelConfig = AIConfigManager.getModelForTask(context, task)
         if (modelConfig.provider == AIProvider.ON_DEVICE) {
             return generateOnDevice(contents, task)
         }

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
                 ParsedResponse(userMessage = response.result ?: "", tools = tools)
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
                 // Tools are configured on the model, just pass contents
                 val response = model.generateContent(*contents.toTypedArray())
                 GeminiFunctionCallParser.parse(response)
             } catch (e: Exception) {
                 Log.e(tag, "Direct API error (custom contents)", e)
                 null
             }
         }
    }

    /**
     * Generate response using on-device inference via MediaPipe.
     * Converts structured history to text, runs local model, parses text response.
     */
    private suspend fun generateOnDevice(
        history: List<Content>,
        task: AITask
    ): ParsedResponse {
        return try {
            val prompt = ContentToTextConverter.convert(history)
            Log.d(tag, "On-device prompt for ${task.displayName} (${prompt.length} chars)")
            val textResponse = onDeviceInferenceManager.generateResponse(prompt)
            TextResponseParser.parseAIResponse(textResponse)
        } catch (e: OnDeviceModelException) {
            Log.e(tag, "On-device model error", e)
            ParsedResponse(
                userMessage = "(On-Device Error: ${e.message})",
                tools = emptyList()
            )
        } catch (e: Exception) {
            Log.e(tag, "On-device inference error", e)
            ParsedResponse(
                userMessage = "(On-Device Error: ${e.message})",
                tools = emptyList()
            )
        }
    }

    /**
     * Parse a backend function call into a ToolCommand.
     */
    private fun parseBackendFunctionCall(fnCall: BackendClient.FunctionCall): ToolCommand? {
        fun getIntValue(elem: JsonElement?): Int? {
            if (elem == null) return null
            return when (elem) {
                is JsonPrimitive -> {
                    // JsonPrimitive.content returns string representation for both strings and numbers
                    // For numeric primitives, content will be "10", "10.5", etc.
                    // For string primitives, content will be the string value
                    try {
                        elem.content.toIntOrNull() ?: elem.content.toDoubleOrNull()?.toInt()
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to parse int from JsonPrimitive: ${elem.content}", e)
                        null
                    }
                }
                else -> {
                    // Try to extract from other JSON types
                    try {
                        elem.toString().toIntOrNull()
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to parse int from JsonElement: ${elem}", e)
                        null
                    }
                }
            }
        }
        
        fun getStringValue(elem: JsonElement?): String? {
            if (elem == null) return null
            return when {
                elem is JsonPrimitive -> elem.content.takeIf { it.isNotBlank() }
                else -> elem.toString().takeIf { it.isNotBlank() && it != "null" }
            }
        }

        Log.d(tag, "Parsing function call: name=${fnCall.name}, args=${fnCall.args}")
        // Log detailed info about the args structure
        fnCall.args.forEach { (key, value) ->
            Log.d(tag, "  arg[$key] = $value (type: ${value?.javaClass?.simpleName}, is JsonPrimitive: ${value is JsonPrimitive}, isString: ${(value as? JsonPrimitive)?.isString})")
        }

        return when (fnCall.name.lowercase()) {
            "allow" -> {
                val minutesArg = fnCall.args["minutes"]
                Log.d(tag, "  minutes arg: $minutesArg (type: ${minutesArg?.javaClass?.simpleName})")
                val minutes = getIntValue(minutesArg)
                val app = getStringValue(fnCall.args["app"])
                Log.d(tag, "Parsing allow: minutes=$minutes, app=$app")
                if (minutes != null && minutes > 0) {
                    val result = ToolCommand.Allow(minutes.minutes, app)
                    Log.i(tag, "Successfully parsed allow command: ${result.duration} for app ${result.app ?: "all"}")
                    result
                } else {
                    Log.w(tag, "Failed to parse allow: minutes=$minutes (must be > 0)")
                    null
                }
            }
            "remember" -> {
                val content = getStringValue(fnCall.args["content"])
                val minutes = getIntValue(fnCall.args["minutes"])
                Log.d(tag, "Parsing remember: content=$content, minutes=$minutes")
                if (content != null) {
                    val result = ToolCommand.Remember(content, minutes?.minutes)
                    Log.i(tag, "Successfully parsed remember command: ${result.content}")
                    result
                } else {
                    Log.w(tag, "Failed to parse remember: content is null or blank")
                    null
                }
            }
            else -> {
                Log.w(tag, "Unknown function call name: ${fnCall.name}")
                null
            }
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
