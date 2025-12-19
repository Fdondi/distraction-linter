package com.timelinter.app

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class EventType {
    MESSAGE,
    TOOL,
    TOOL_ERROR,
    STATE,
    APP,
    BUCKET,
    SYSTEM
}

@Serializable
data class EventLogEntry(
    val id: Long,
    val timestamp: Long,
    val type: EventType,
    val title: String,
    val details: String? = null,
    val role: String? = null
)

object EventLogStore {
    // Keep the clock provider first so any property initializers that call today() are safe
    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    private val _events = MutableStateFlow<List<EventLogEntry>>(emptyList())
    val events: StateFlow<List<EventLogEntry>> = _events.asStateFlow()

    private val _availableDays = MutableStateFlow<List<LocalDate>>(emptyList())
    val availableDays: StateFlow<List<LocalDate>> = _availableDays.asStateFlow()

    private val _currentDay = MutableStateFlow(today())
    val currentDay: StateFlow<LocalDate> = _currentDay.asStateFlow()

    private var nextId = 1L
    private var contextLineProvider: (() -> String?)? = null
    private var persistence: EventLogPersistence? = null
    private var retentionDays: Int? = null

    fun clear(clearPersistence: Boolean = false) {
        _events.value = emptyList()
        _availableDays.value = listOf(today())
        _currentDay.value = today()
        nextId = 1L
        if (clearPersistence) {
            persistence?.clearAll()
        }
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

    fun setContextLineProvider(provider: (() -> String?)?) {
        contextLineProvider = provider
    }

    fun configurePersistenceIfNeeded(context: Context, retentionDaysOverride: Int? = null) {
        if (persistence != null) {
            val changed = retentionDaysOverride != this.retentionDays
            if (changed) {
                retentionDays = retentionDaysOverride
                persistence?.setRetentionDays(retentionDaysOverride)
                persistence?.pruneIfNeeded(currentDate = today())
                refreshAvailableDays()
            }
            return
        }
        val dir = File(context.filesDir, "event_logs")
        persistence = EventLogPersistence(dir, retentionDaysOverride)
        retentionDays = retentionDaysOverride
        refreshAvailableDays()
        loadDay(today())
        persistence?.pruneIfNeeded(currentDate = today())
    }

    fun setRetentionDays(days: Int?) {
        retentionDays = days
        persistence?.setRetentionDays(days)
        persistence?.pruneIfNeeded(currentDate = today())
        refreshAvailableDays()
    }

    fun loadDay(day: LocalDate) {
        _currentDay.value = day
        val eventsForDay = persistence?.load(day)?.sortedByDescending { it.timestamp } ?: run {
            if (day == today()) _events.value else emptyList()
        }
        _events.value = eventsForDay
    }

    fun getAvailableDays(): List<LocalDate> {
        val days = persistence?.listDays()
        return if (days.isNullOrEmpty()) {
            listOf(today())
        } else {
            days
        }
    }

    fun setNowProviderForTests(provider: () -> Long) {
        nowProvider = provider
        // Reset current day when the clock changes to avoid stale value
        _currentDay.value = today()
        refreshAvailableDays()
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

    fun logToolParseFailure(issue: ToolCallIssue) {
        val reasonText = when (issue.reason) {
            ToolCallIssueReason.TEXT_TOOL_FORMAT -> "Tool call was written as text and was not executed."
            ToolCallIssueReason.INVALID_ARGS -> "Tool call had invalid arguments."
            ToolCallIssueReason.UNSUPPORTED_TOOL -> "Tool name not supported."
        }
        val details = "${issue.rawText} — $reasonText"
        addEvent(EventType.TOOL_ERROR, "Tool parse failed", details, role = "model")
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
        val contextLine = contextLineProvider?.invoke()
        val mergedDetails = buildString {
            if (!details.isNullOrBlank()) {
                append(details.trimEnd())
                if (contextLine != null) appendLine()
            }
            if (!contextLine.isNullOrBlank()) {
                append(contextLine)
            }
        }.ifBlank { null }

        val entry = EventLogEntry(
            id = nextId++,
            timestamp = nowProvider.invoke(),
            type = type,
            title = title,
            details = mergedDetails,
            role = role
        )
        // Prepend in-memory list for the currently viewed day
        val entryDay = entry.asLocalDate()
        if (entryDay == _currentDay.value) {
            _events.value = listOf(entry) + _events.value
        }
        persistence?.append(entry)
        refreshAvailableDays()
    }

    private fun today(): LocalDate {
        return Instant.ofEpochMilli(nowProvider.invoke()).atZone(ZoneOffset.UTC).toLocalDate()
    }

    private fun refreshAvailableDays() {
        _availableDays.value = getAvailableDays()
        if (!_availableDays.value.contains(_currentDay.value)) {
            _currentDay.value = _availableDays.value.firstOrNull() ?: today()
        }
    }
}

private fun EventLogEntry.asLocalDate(): LocalDate {
    return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
}

