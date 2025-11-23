package com.example.timelinter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EventType {
    MESSAGE,
    TOOL,
    STATE,
    APP,
    BUCKET,
    SYSTEM
}

data class EventLogEntry(
    val id: Long,
    val timestamp: Long,
    val type: EventType,
    val title: String,
    val details: String? = null,
    val role: String? = null
)

object EventLogStore {
    private val _events = MutableStateFlow<List<EventLogEntry>>(emptyList())
    val events: StateFlow<List<EventLogEntry>> = _events.asStateFlow()

    private var nextId = 1L

    fun clear() {
        _events.value = emptyList()
        nextId = 1L
    }

    fun search(query: String): List<EventLogEntry> {
        if (query.isBlank()) return _events.value
        val q = query.trim().lowercase()
        return _events.value.filter { entry ->
            (entry.title.lowercase().contains(q)) ||
            (entry.details?.lowercase()?.contains(q) == true) ||
            (entry.role?.lowercase()?.contains(q) == true) ||
            entry.type.name.lowercase().contains(q)
        }
    }

    fun logMessage(role: String, text: String) {
        val label = when (role.lowercase()) {
            "user" -> "Message from user"
            "ai", "model" -> "Message from AI"
            "system" -> "System message"
            else -> "Message ($role)"
        }
        addEvent(EventType.MESSAGE, label, text, role = role)
    }

    fun logTool(tool: ToolCommand) {
        val label: String
        val details: String
        when (tool) {
            is ToolCommand.Allow -> {
                label = "Tool: allow"
                val appPart = tool.app?.let { ", app=\"$it\"" } ?: ""
                details = "Granted ${tool.duration.inWholeMinutes} minutes$appPart"
            }
            is ToolCommand.Remember -> {
                label = "Tool: remember"
                val dur = tool.duration?.inWholeMinutes?.let { ", minutes=$it" } ?: ""
                details = "\"${tool.content}\"$dur"
            }
        }
        addEvent(EventType.TOOL, label, details)
    }

    fun logStateChange(stateName: String) {
        addEvent(EventType.STATE, "State changed", stateName)
    }

    fun logSessionStarted(appName: String) {
        addEvent(EventType.SYSTEM, "Session started", "App: $appName")
    }

    fun logSessionReset(reason: String) {
        addEvent(EventType.SYSTEM, "Session reset", reason)
    }

    fun logAppChanged(previous: String?, current: String?) {
        val prev = previous ?: "None"
        val curr = current ?: "None"
        addEvent(EventType.APP, "App changed", "$prev → $curr")
    }

    fun logNewWastefulAppDetected(appName: String) {
        addEvent(EventType.APP, "New wasteful app detected", appName)
    }

    fun logBucketRefilledAndReset() {
        addEvent(EventType.BUCKET, "Bucket refilled to full", "Archived and cleared conversation")
    }

    fun logSystem(note: String) {
        addEvent(EventType.SYSTEM, "System", note)
    }

    private fun addEvent(type: EventType, title: String, details: String? = null, role: String? = null) {
        val entry = EventLogEntry(
            id = nextId++,
            timestamp = System.currentTimeMillis(),
            type = type,
            title = title,
            details = details,
            role = role
        )
        // Prepend so the most recent events are always at the top
        _events.value = listOf(entry) + _events.value
    }
}


