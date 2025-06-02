package com.example.timelinter

import android.util.Log

data class ParsedResponse(
    val userMessage: String, // Message to show to user (with tools stripped)
    val tools: List<ToolCommand>
)

sealed class ToolCommand {
    data class Allow(val minutes: Int, val app: String? = null) : ToolCommand()
    data class Remember(val content: String, val durationMinutes: Int? = null) : ToolCommand() // null = FOREVER
}

object ToolParser {
    private const val TAG = "ToolParser"

    fun parseAIResponse(rawResponse: String): ParsedResponse {
        val lines = rawResponse.lines()
        val tools = mutableListOf<ToolCommand>()
        val userMessageLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            
            when {
                trimmedLine.startsWith("# ALLOW") -> {
                    parseAllowCommand(trimmedLine)?.let { tools.add(it) }
                }
                trimmedLine.startsWith("# REMEMBER") -> {
                    parseRememberCommand(trimmedLine)?.let { tools.add(it) }
                }
                else -> {
                    // Regular message line
                    userMessageLines.add(line)
                }
            }
        }

        val userMessage = userMessageLines.joinToString("\n").trim()
        
        Log.d(TAG, "Parsed response: ${tools.size} tools, message: '${userMessage.take(50)}...'")
        
        return ParsedResponse(userMessage, tools)
    }

    private fun parseAllowCommand(line: String): ToolCommand.Allow? {
        // Format: # ALLOW [minutes] [[app]]
        // Examples: "# ALLOW 30", "# ALLOW 15 YouTube"
        
        val parts = line.removePrefix("# ALLOW").trim().split(" ", limit = 2)
        
        if (parts.isEmpty() || parts[0].isEmpty()) {
            Log.w(TAG, "Invalid ALLOW command: $line")
            return null
        }

        val minutes = parts[0].toIntOrNull()
        if (minutes == null || minutes <= 0) {
            Log.w(TAG, "Invalid minutes in ALLOW command: $line")
            return null
        }

        val app = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1] else null
        
        Log.d(TAG, "Parsed ALLOW: $minutes minutes${app?.let { " for $it" } ?: ""}")
        return ToolCommand.Allow(minutes, app)
    }

    private fun parseRememberCommand(line: String): ToolCommand.Remember? {
        // Format: # REMEMBER [minutes]: [content] OR # REMEMBER FOREVER: [content]
        // Examples: "# REMEMBER 60: Be extra strict", "# REMEMBER FOREVER: On Wednesdays 1h of Instagram is for work"
        
        val withoutPrefix = line.removePrefix("# REMEMBER").trim()
        
        if (!withoutPrefix.contains(":")) {
            Log.w(TAG, "Invalid REMEMBER command format (missing colon): $line")
            return null
        }

        val colonIndex = withoutPrefix.indexOf(":")
        val durationPart = withoutPrefix.substring(0, colonIndex).trim()
        val content = withoutPrefix.substring(colonIndex + 1).trim()

        if (content.isEmpty()) {
            Log.w(TAG, "Empty content in REMEMBER command: $line")
            return null
        }

        val durationMinutes = when (durationPart.uppercase()) {
            "FOREVER" -> null
            else -> {
                durationPart.toIntOrNull()?.takeIf { it > 0 }
                    ?: run {
                        Log.w(TAG, "Invalid duration in REMEMBER command: $line")
                        return null
                    }
            }
        }

        Log.d(TAG, "Parsed REMEMBER: '${content.take(30)}...' for ${durationMinutes?.let { "$it minutes" } ?: "FOREVER"}")
        return ToolCommand.Remember(content, durationMinutes)
    }
} 