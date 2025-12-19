package com.timelinter.app

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
        val toolErrors = mutableListOf<ToolCallIssue>()

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
                            // Extract inline tool calls inside the text (e.g., "...achievement.allow(5)")
                            val inlineResult = extractInlineTools(part.text)
                            tools += inlineResult.tools
                            toolErrors += inlineResult.issues

                            // Split into lines, drop tool-like lines, but remember them as failed attempts
                            val filtered = filterOutToolLikeLines(inlineResult.cleanedText.lines())
                            val cleaned = filtered.cleanedLines.joinToString("\n").trim()
                            if (cleaned.isNotEmpty()) textParts.add(cleaned)
                            filtered.toolLikeLines.forEach { line ->
                                toolErrors.add(
                                    ToolCallIssue(
                                        reason = ToolCallIssueReason.TEXT_TOOL_FORMAT,
                                        rawText = line.trim()
                                    )
                                )
                            }
                        }
                        else -> {
                            // Attempt to reflectively read function call
                            val simple = part::class.java.simpleName
                            if (simple.equals("FunctionCall", ignoreCase = true)) {
                                val outcome = extractToolFromFunctionCall(part)
                                outcome.tool?.let { tools.add(it) }
                                outcome.issue?.let { toolErrors.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting response part: ${e.message}")
                }
            }
        }

        val message = textParts.joinToString("\n").trim()
        return ParsedResponse(userMessage = message, tools = tools, toolErrors = toolErrors)
    }

    private fun extractToolFromFunctionCall(part: Any): ExtractionOutcome {
        return try {
            val cls = part::class.java
            val name = cls.methods.firstOrNull { it.name == "getName" }?.invoke(part) as? String
                ?: return ExtractionOutcome()

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
                    val tool = minutes?.takeIf { it > 0 }?.let { ToolCommand.Allow(it.minutes, app) }
                    if (tool != null) {
                        ExtractionOutcome(tool = tool)
                    } else {
                        ExtractionOutcome(
                            issue = ToolCallIssue(
                                reason = ToolCallIssueReason.INVALID_ARGS,
                                rawText = buildRawCall(name, argsMap)
                            )
                        )
                    }
                }
                "remember" -> {
                    val content = argsMap["content"]?.toString()?.takeIf { it.isNotBlank() }
                    val minutes = (argsMap["minutes"] as? Number)?.toInt()
                        ?: (argsMap["minutes"] as? String)?.toIntOrNull()
                    val tool = content?.let { ToolCommand.Remember(it, minutes?.minutes) }
                    if (tool != null) {
                        ExtractionOutcome(tool = tool)
                    } else {
                        ExtractionOutcome(
                            issue = ToolCallIssue(
                                reason = ToolCallIssueReason.INVALID_ARGS,
                                rawText = buildRawCall(name, argsMap)
                            )
                        )
                    }
                }
                else -> {
                    ExtractionOutcome(
                        issue = ToolCallIssue(
                            reason = ToolCallIssueReason.UNSUPPORTED_TOOL,
                            rawText = buildRawCall(name, argsMap)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract function call: ${e.message}")
            ExtractionOutcome(
                issue = ToolCallIssue(
                    reason = ToolCallIssueReason.INVALID_ARGS,
                    rawText = part.toString()
                )
            )
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

    private fun filterOutToolLikeLines(lines: List<String>): FilterResult {
        val toolPatterns = listOf(
            Regex("^\\s*#\\s*ALLOW\\b.*", RegexOption.IGNORE_CASE),
            Regex("^\\s*#\\s*REMEMBER\\b.*", RegexOption.IGNORE_CASE),
            Regex("^\\s*allow\\s*\\(.*\\)\\s*$", RegexOption.IGNORE_CASE),
            Regex("^\\s*remember\\s*\\(.*\\)\\s*$", RegexOption.IGNORE_CASE)
        )
        val cleaned = mutableListOf<String>()
        val toolLines = mutableListOf<String>()
        lines.forEach { line ->
            if (toolPatterns.any { it.containsMatchIn(line) }) {
                toolLines.add(line)
            } else {
                cleaned.add(line)
            }
        }
        return FilterResult(cleanedLines = cleaned, toolLikeLines = toolLines)
    }

    private fun buildRawCall(name: String, args: Map<String, Any?>): String {
        val renderedArgs = args.entries.joinToString(", ") { (k, v) -> "$k=$v" }
        return "$name($renderedArgs)"
    }

    private fun extractInlineTools(text: String): InlineExtractionResult {
        var cursor = 0
        val cleaned = StringBuilder()
        val tools = mutableListOf<ToolCommand>()
        val issues = mutableListOf<ToolCallIssue>()

        INLINE_TOOL_REGEX.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                cleaned.append(text.substring(cursor, match.range.first))
            }
            val name = match.groups["name"]?.value?.lowercase()
            val minutesStr = match.groups["minutes"]?.value
            val app = match.groups["app"]?.value?.takeIf { it.isNotBlank() }
            val content = match.groups["content"]?.value

            when (name) {
                "allow" -> {
                    val minutes = minutesStr?.toIntOrNull()
                    if (minutes != null && minutes > 0) {
                        tools.add(ToolCommand.Allow(minutes.minutes, app))
                    } else {
                        issues.add(
                            ToolCallIssue(
                                reason = ToolCallIssueReason.INVALID_ARGS,
                                rawText = match.value
                            )
                        )
                    }
                }
                "remember" -> {
                    if (!content.isNullOrBlank()) {
                        val minutes = minutesStr?.toIntOrNull()
                        tools.add(ToolCommand.Remember(content, minutes?.minutes))
                    } else {
                        issues.add(
                            ToolCallIssue(
                                reason = ToolCallIssueReason.INVALID_ARGS,
                                rawText = match.value
                            )
                        )
                    }
                }
                else -> {
                    issues.add(
                        ToolCallIssue(
                            reason = ToolCallIssueReason.UNSUPPORTED_TOOL,
                            rawText = match.value
                        )
                    )
                }
            }

            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            cleaned.append(text.substring(cursor))
        }

        return InlineExtractionResult(cleaned.toString(), tools, issues)
    }

    private data class InlineExtractionResult(
        val cleanedText: String,
        val tools: List<ToolCommand>,
        val issues: List<ToolCallIssue>,
    )

    // Matches allow(5) or remember("note", 10) even when embedded in text
    private val INLINE_TOOL_REGEX = Regex(
        """(?<name>allow|remember)\s*\(\s*(?:(?<minutes>\d+)\s*(?:,\s*"(?<app>[^"]*)"\s*)?| "(?<content>[^"]+)"\s*(?:,\s*(?<minutes>\d+))?\s*)\)""",
        RegexOption.IGNORE_CASE
    )

    private data class FilterResult(
        val cleanedLines: List<String>,
        val toolLikeLines: List<String>,
    )

    private data class ExtractionOutcome(
        val tool: ToolCommand? = null,
        val issue: ToolCallIssue? = null,
    )
}



