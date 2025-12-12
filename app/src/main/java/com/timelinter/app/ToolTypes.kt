package com.timelinter.app

import kotlin.time.Duration

// Shared tool data types used by function-calling path

data class ParsedResponse(
    val userMessage: String,
    val tools: List<ToolCommand>,
    val authExpired: Boolean = false,
)

sealed class ToolCommand {
    data class Allow(val duration: Duration, val app: String? = null) : ToolCommand()
    data class Remember(val content: String, val duration: Duration? = null) : ToolCommand()
}



