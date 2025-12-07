package com.timelinter.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Test helper to parse text-based function calls from AI responses.
 * Used for testing function call parsing without requiring GenerateContentResponse objects.
 */
object TextResponseParser {
    
    fun parseAIResponse(text: String): ParsedResponse {
        val tools = mutableListOf<ToolCommand>()
        val lines = text.lines()
        val messageParts = mutableListOf<String>()
        
        for (line in lines) {
            val tool = parseTool(line)
            if (tool != null) {
                tools.add(tool)
            } else {
                // If it's not a tool line, it's part of the message
                if (line.trim().isNotEmpty()) {
                    messageParts.add(line)
                }
            }
        }
        
        return ParsedResponse(
            userMessage = messageParts.joinToString("\n").trim(),
            tools = tools
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
}

// Global function for backward compatibility with existing tests
fun parseAIResponse(text: String): ParsedResponse = TextResponseParser.parseAIResponse(text)


