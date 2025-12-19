package com.timelinter.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Test helper to parse text-based function calls from AI responses.
 * Used for testing function call parsing without requiring GenerateContentResponse objects.
 */
object TextResponseParser {
    
    fun parseAIResponse(text: String): ParsedResponse {
        val inline = extractInlineTools(text)
        val tools = mutableListOf<ToolCommand>()
        tools += inline.tools
        val lines = inline.cleanedText.lines()
        val messageParts = mutableListOf<String>()
        val toolErrors = mutableListOf<ToolCallIssue>()
        toolErrors += inline.toolErrors
        
        for (line in lines) {
            val tool = parseTool(line)
            if (tool != null) {
                tools.add(tool)
            } else {
                val trimmed = line.trim()
                // If it's a tool-like line but not valid, record as issue and drop from message
                if (trimmed.isNotEmpty()) {
                    if (isToolLikeLine(trimmed)) {
                        toolErrors.add(
                            ToolCallIssue(
                                reason = ToolCallIssueReason.TEXT_TOOL_FORMAT,
                                rawText = trimmed
                            )
                        )
                    } else {
                        messageParts.add(line)
                    }
                }
            }
        }
        
        return ParsedResponse(
            userMessage = messageParts.joinToString("\n").trim(),
            tools = tools,
            toolErrors = toolErrors
        )
    }
    
    private fun parseTool(line: String): ToolCommand? {
        val trimmed = line.trim()
        
        // Parse remember() calls
        val rememberPattern = """remember\s*\(\s*"([^"]*)"\s*(?:,\s*(\d+))?\s*\)""".toRegex()
        val rememberMatch = rememberPattern.find(trimmed)
        if (rememberMatch != null) {
            val content = rememberMatch.groupValues[1]
            val durationMinutes = rememberMatch.groupValues.getOrNull(2)?.toIntOrNull()
            val duration: Duration? = durationMinutes?.minutes
            return ToolCommand.Remember(content, duration)
        }
        
        // Parse allow() calls
        val allowPattern = """allow\s*\(\s*(\d+)\s*(?:,\s*"([^"]*)")?\s*\)""".toRegex()
        val allowMatch = allowPattern.find(trimmed)
        if (allowMatch != null) {
            val minutes = allowMatch.groupValues[1].toInt()
            val app = allowMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            return ToolCommand.Allow(minutes.minutes, app)
        }
        
        return null
    }

    private fun isToolLikeLine(trimmed: String): Boolean {
        return TOOL_LINE_REGEX.containsMatchIn(trimmed)
    }

    private val TOOL_LINE_REGEX =
        """^\s*(allow|remember)\s*\(.*\)\s*$""".toRegex(RegexOption.IGNORE_CASE)

    private fun extractInlineTools(text: String): InlineParseResult {
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

        return InlineParseResult(cleaned.toString(), tools, issues)
    }

    private data class InlineParseResult(
        val cleanedText: String,
        val tools: List<ToolCommand>,
        val toolErrors: List<ToolCallIssue>,
    )

    // Matches allow(5) or remember("note", 10) even inline with text
    private val INLINE_TOOL_REGEX = Regex(
        """(?<name>allow|remember)\s*\(\s*(?:(?<minutes>\d+)\s*(?:,\s*"(?<app>[^"]*)"\s*)?| "(?<content>[^"]+)"\s*(?:,\s*(?<minutes>\d+))?\s*)\)""",
        RegexOption.IGNORE_CASE
    )
}

// Global function for backward compatibility with existing tests
fun parseAIResponse(text: String): ParsedResponse = TextResponseParser.parseAIResponse(text)


