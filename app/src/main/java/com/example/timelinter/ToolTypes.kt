package com.example.timelinter

// Shared tool data types used by function-calling path

data class ParsedResponse(
    val userMessage: String,
    val tools: List<ToolCommand>
)

sealed class ToolCommand {
    data class Allow(val minutes: Int, val app: String? = null) : ToolCommand()
    data class Remember(val content: String, val durationMinutes: Int? = null) : ToolCommand()
}



