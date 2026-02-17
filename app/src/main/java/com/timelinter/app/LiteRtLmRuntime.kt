package com.timelinter.app

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.minutes
import com.google.ai.edge.litertlm.Contents as LiteRtContents

/**
 * On-device runtime backed by LiteRT-LM.
 *
 * Uses the native Conversation API with:
 * - Structured tool calling (allow, remember) captured via OpenApiTool
 * - Streaming + cancelProcess() for hard output limits that save GPU work
 */
class LiteRtLmRuntime(private val context: Context) : OnDeviceRuntime {

    private val tag = "LiteRtLmRuntime"

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var preferredBackend: Backend = Backend.GPU
    private val mutex = Mutex()

    override suspend fun generateResponse(
        history: List<Content>,
        maxOutputTokens: Int
    ): ParsedResponse = withContext(Dispatchers.IO) {
        // #region agent log
        Log.w(tag, "DBG-STEP-1: generateResponse called, backend=$preferredBackend, historySize=${history.size}")
        // #endregion
        try {
            doGenerate(history, maxOutputTokens)
        } catch (e: OnDeviceModelException) {
            val isOpenCl = e.message?.contains("OpenCL") == true
            if (isOpenCl && preferredBackend != Backend.CPU) {
                // #region agent log
                Log.w(tag, "DBG-FALLBACK: OpenCL error with GPU, switching to CPU and retrying")
                // #endregion
                Log.w(tag, "GPU inference failed (OpenCL unavailable), reinitializing with CPU backend")
                preferredBackend = Backend.CPU
                forceReinitialize()
                doGenerate(history, maxOutputTokens)
            } else {
                throw e
            }
        }
    }

    private suspend fun doGenerate(
        history: List<Content>,
        maxOutputTokens: Int
    ): ParsedResponse {
        val eng = getOrCreateEngine()
        // #region agent log
        Log.w(tag, "DBG-STEP-2: engine obtained, backend=$preferredBackend")
        // #endregion

        val (systemText, initialMessages, lastUserText) = convertHistory(history)

        val capturedTools = mutableListOf<ToolCommand>()
        val toolProviders = createCapturingTools(capturedTools)

        val conversationConfig = ConversationConfig(
            systemInstruction = systemText?.let { LiteRtContents.of(it) },
            initialMessages = initialMessages,
            tools = toolProviders,
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = 0.7,
            ),
        )

        val conversation = eng.createConversation(conversationConfig)

        val maxChars = maxOutputTokens * 4
        val chunks = StringBuilder()
        var flowError: Throwable? = null

        // #region agent log
        Log.w(tag, "DBG-STEP-3: sendMessageAsync with backend=$preferredBackend")
        // #endregion
        try {
            conversation.sendMessageAsync(lastUserText).collect { message ->
                val chunk = message.toString()
                chunks.append(chunk)
                // #region agent log
                if (chunks.length <= 200) Log.w(tag, "DBG-CHUNK: len=${chunks.length}, chunk='${chunk.take(40)}'")
                // #endregion
                if (chunks.length >= maxChars) {
                    conversation.cancelProcess()
                }
            }
        } catch (e: Exception) {
            flowError = e
            // #region agent log
            Log.w(tag, "DBG-STEP-4-ERROR: ${e.javaClass.name}: ${e.message}")
            // #endregion
            Log.d(tag, "Stream ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                conversation.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing conversation", e)
            }
        }

        // #region agent log
        Log.w(tag, "DBG-STEP-5: done, chunks.len=${chunks.length}, error=${flowError?.javaClass?.simpleName}")
        // #endregion

        if (chunks.isEmpty() && flowError != null) {
            throw OnDeviceModelException("LiteRT-LM inference failed: ${flowError.message}")
        }

        val textParsed = TextResponseParser.parseAIResponse(chunks.toString())
        val allTools = capturedTools + textParsed.tools

        return ParsedResponse(
            userMessage = textParsed.userMessage,
            tools = allTools,
            toolErrors = textParsed.toolErrors,
        )
    }

    override suspend fun release() {
        mutex.withLock {
            engine?.close()
            engine = null
            loadedModelPath = null
            Log.i(tag, "LiteRT-LM engine released")
        }
    }

    private suspend fun forceReinitialize() {
        mutex.withLock {
            engine?.close()
            engine = null
            loadedModelPath = null
        }
    }

    private suspend fun getOrCreateEngine(): Engine = mutex.withLock {
        val configuredPath = SettingsManager.getLiteRtModelPath(context)
            ?: throw OnDeviceModelException("LiteRT-LM model path not configured. Set it in AI Configuration.")

        if (engine != null && loadedModelPath == configuredPath) {
            return@withLock engine!!
        }

        if (engine != null) {
            Log.i(tag, "Model path changed, releasing previous engine")
            engine?.close()
            engine = null
        }

        val modelFile = File(configuredPath)
        if (!modelFile.exists()) {
            throw OnDeviceModelException(
                "Model file not found at: $configuredPath. " +
                "Push a .litertlm file to the device and update the path in AI Configuration."
            )
        }

        // #region agent log
        Log.w(tag, "DBG-INIT: Initializing engine with backend=$preferredBackend, path=$configuredPath")
        // #endregion
        Log.i(tag, "Loading LiteRT-LM model from: $configuredPath (backend=$preferredBackend)")
        val config = EngineConfig(
            modelPath = configuredPath,
            backend = preferredBackend,
            cacheDir = context.cacheDir.path,
        )
        val eng = Engine(config)
        eng.initialize()
        engine = eng
        loadedModelPath = configuredPath
        Log.i(tag, "LiteRT-LM engine loaded successfully (backend=$preferredBackend)")
        eng
    }

    /**
     * Convert Gemini SDK history into LiteRT-LM format.
     *
     * Returns (systemText, initialMessages, lastUserText):
     * - systemText: the system prompt (first user turn in our history convention)
     * - initialMessages: all turns except the first (system) and last (pending user)
     * - lastUserText: the last user message to send via sendMessage()
     */
    private fun convertHistory(
        history: List<Content>
    ): Triple<String?, List<Message>, String> {
        if (history.isEmpty()) {
            return Triple(null, emptyList(), "")
        }

        fun extractText(content: Content): String {
            return content.parts
                .filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }
        }

        // First content is system prompt in our convention
        val systemText = if (history.isNotEmpty()) extractText(history.first()) else null

        // Middle turns become initialMessages
        val middleTurns = if (history.size > 2) history.subList(1, history.size - 1) else emptyList()
        val initialMessages = middleTurns.map { content ->
            val role = content.role ?: "user"
            val text = extractText(content)
            if (role == "model") Message.model(text) else Message.user(text)
        }

        // Last turn is what we send via sendMessage
        val lastContent = history.last()
        val lastText = extractText(lastContent)
        val lastRole = lastContent.role ?: "user"

        // If the last turn happens to be "model" (unusual), include it in initial messages
        // and send a minimal user prompt
        return if (lastRole == "model" && history.size > 1) {
            val allMessages = initialMessages + Message.model(lastText)
            Triple(systemText, allMessages, "Continue.")
        } else {
            Triple(systemText, initialMessages, lastText)
        }
    }

    /**
     * Create OpenApiTool implementations that capture tool calls into [captured]
     * while returning acknowledgment strings back to the model.
     */
    private fun createCapturingTools(
        captured: MutableList<ToolCommand>
    ): List<Any> {
        val allowTool = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = """
                {
                  "name": "allow",
                  "description": "Grant the user extra time on the current app",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "minutes": {
                        "type": "integer",
                        "description": "Number of minutes to grant"
                      },
                      "app": {
                        "type": "string",
                        "description": "Optional: specific app name to grant time for"
                      }
                    },
                    "required": ["minutes"]
                  }
                }
            """.trimIndent()

            override fun execute(paramsJsonString: String): String {
                val params = JsonParser.parseString(paramsJsonString).asJsonObject
                val minutesVal = params.get("minutes")?.asInt ?: return "Error: missing minutes"
                val appElement = params.get("app")
                val app = if (appElement != null && !appElement.isJsonNull) appElement.asString else null
                captured.add(ToolCommand.Allow(minutesVal.minutes, app))
                Log.d(tag, "Tool captured: allow($minutesVal, ${app ?: "all apps"})")
                return if (app != null) "Granted $minutesVal minutes for $app" else "Granted $minutesVal minutes"
            }
        }

        val rememberTool = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = """
                {
                  "name": "remember",
                  "description": "Store a memory note about the user for future conversations",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "content": {
                        "type": "string",
                        "description": "The information to remember"
                      },
                      "minutes": {
                        "type": "integer",
                        "description": "Optional: how many minutes this memory should last (omit for permanent)"
                      }
                    },
                    "required": ["content"]
                  }
                }
            """.trimIndent()

            override fun execute(paramsJsonString: String): String {
                val params = JsonParser.parseString(paramsJsonString).asJsonObject
                val memContent = params.get("content")?.asString ?: return "Error: missing content"
                val minutesElement = params.get("minutes")
                val durationMin = if (minutesElement != null && !minutesElement.isJsonNull) minutesElement.asInt else null
                captured.add(ToolCommand.Remember(memContent, durationMin?.minutes))
                Log.d(tag, "Tool captured: remember(\"$memContent\", ${durationMin ?: "permanent"})")
                return "Noted: $memContent"
            }
        }

        return listOf(tool(allowTool), tool(rememberTool))
    }
}
