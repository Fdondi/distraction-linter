package com.timelinter.app

import kotlin.time.Duration

// Shared tool data types used by function-calling path

data class ParsedResponse(
    val userMessage: String,
    val tools: List<ToolCommand>,
    val authExpired: Boolean = false,
    val toolErrors: List<ToolCallIssue> = emptyList(),
)

sealed class ToolCommand {
    data class Allow(val duration: Duration, val app: String? = null) : ToolCommand()
    data class Remember(val content: String, val duration: Duration? = null) : ToolCommand()
}

data class ToolCallIssue(
    val reason: ToolCallIssueReason,
    val rawText: String,
)

enum class ToolCallIssueReason {
    TEXT_TOOL_FORMAT,
    INVALID_ARGS,
    UNSUPPORTED_TOOL,
}



