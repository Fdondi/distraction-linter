package com.example.timelinter

import android.util.Log
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.TextPart
import kotlin.time.Duration.Companion.minutes

/**
 * Parses Gemini function calls (if present) from a GenerateContentResponse.
 * Falls back to collecting text parts as the user-visible message.
 *
 * This uses reflection to avoid tight coupling to SDK internals and supports
 * environments where function-calling types may vary by version.
 */
object GeminiFunctionCallParser {
    private const val TAG = "GeminiFnCallParser"

    fun parse(response: GenerateContentResponse): ParsedResponse {
        val tools = mutableListOf<ToolCommand>()
        val textParts = mutableListOf<String>()

        val candidates = try {
            response.candidates
        } catch (e: Exception) { emptyList() }
        candidates.forEach { candidate ->
            val content = try { candidate.content } catch (e: Exception) { null }
            val parts = try { content?.parts ?: emptyList() } catch (e: Exception) { emptyList() }
            parts.forEach { part ->
                try {
                    when (part) {
                        is TextPart -> {
                            // Split into lines and drop tool-like lines
                            val cleaned = filterOutToolLikeLines(part.text.lines()).joinToString("\n").trim()
                            if (cleaned.isNotEmpty()) textParts.add(cleaned)
                        }
                        else -> {
                            // Attempt to reflectively read function call
                            val simple = part::class.java.simpleName
                            if (simple.equals("FunctionCall", ignoreCase = true)) {
                                extractToolFromFunctionCall(part)?.let { tools.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting response part: ${e.message}")
                }
            }
        }

        val message = textParts.joinToString("\n").trim()
        return ParsedResponse(userMessage = message, tools = tools)
    }

    private fun extractToolFromFunctionCall(part: Any): ToolCommand? {
        return try {
            val cls = part::class.java
            val name = cls.methods.firstOrNull { it.name == "getName" }?.invoke(part) as? String
                ?: return null

            // args may be Map-like or JSON-like; try getArgs() and convert to Map<String, Any?> via toString parse for simple keys
            val argsObj = cls.methods.firstOrNull { it.name == "getArgs" }?.invoke(part)
            val argsMap: Map<String, Any?> = when (argsObj) {
                is Map<*, *> -> argsObj.entries.filter { it.key is String }.associate { (k, v) -> k as String to v }
                else -> parseArgsFromToString(argsObj?.toString())
            }

            when (name.lowercase()) {
                "allow" -> {
                    val minutes = (argsMap["minutes"] as? Number)?.toInt()
                        ?: (argsMap["minutes"] as? String)?.toIntOrNull()
                    val app = argsMap["app"]?.toString()?.takeIf { it.isNotBlank() }
                    minutes?.takeIf { it > 0 }?.let { ToolCommand.Allow(it.minutes, app) }
                }
                "remember" -> {
                    val content = argsMap["content"]?.toString()?.takeIf { it.isNotBlank() }
                    val minutes = (argsMap["minutes"] as? Number)?.toInt()
                        ?: (argsMap["minutes"] as? String)?.toIntOrNull()
                    content?.let { ToolCommand.Remember(it, minutes?.minutes) }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract function call: ${e.message}")
            null
        }
    }

    private fun parseArgsFromToString(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) return emptyMap()
        // Very simple key=value parser for strings like: {minutes=10, app=YouTube}
        val trimmed = text.trim().removePrefix("{").removeSuffix("}")
        if (trimmed.isBlank()) return emptyMap()
        return trimmed.split(",").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.take(idx).trim()
            val raw = pair.substring(idx + 1).trim()
            val value: Any = raw.toIntOrNull() ?: raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw.removeSurrounding("\"", "\"")
            key to value
        }.toMap()
    }

    private fun filterOutToolLikeLines(lines: List<String>): List<String> {
        val toolPatterns = listOf(
            Regex("^\\s*#\\s*ALLOW\\b.*", RegexOption.IGNORE_CASE),
            Regex("^\\s*#\\s*REMEMBER\\b.*", RegexOption.IGNORE_CASE),
            Regex("^\\s*allow\\s*\\(.*\\)\\s*$", RegexOption.IGNORE_CASE),
            Regex("^\\s*remember\\s*\\(.*\\)\\s*$", RegexOption.IGNORE_CASE)
        )
        return lines.filter { line ->
            toolPatterns.none { it.containsMatchIn(line) }
        }
    }
}



