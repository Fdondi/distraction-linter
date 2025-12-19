package com.timelinter.app

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists event logs segmented by day. Files are named as YYYY-MM-DD.json inside the provided
 * directory. Retention (in days) is optional; when set, files older than the retention window are
 * deleted during writes and explicit prune calls.
 */
class EventLogPersistence(
    private val rootDir: File,
    retentionDays: Int? = null,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var retentionDays: Int? = retentionDays

    init {
        rootDir.mkdirs()
    }

    fun setRetentionDays(days: Int?) {
        retentionDays = days
    }

    fun append(entry: EventLogEntry) {
        val day = entry.localDate()
        val file = fileForDay(day)
        val existing = readFile(file)
        // Prepend newest to keep reverse chronological order
        val updated = listOf(entry) + existing
        writeFile(file, updated)
        pruneIfNeeded(currentDate = day)
    }

    fun load(day: LocalDate): List<EventLogEntry> {
        val file = fileForDay(day)
        return readFile(file)
    }

    fun listDays(): List<LocalDate> {
        if (!rootDir.exists()) return emptyList()
        return rootDir
            .listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
            }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun clearAll() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        rootDir.mkdirs()
    }

    fun pruneIfNeeded(currentDate: LocalDate = LocalDate.now(ZoneOffset.UTC)) {
        val retention = retentionDays
        if (retention == null || retention <= 0) return

        val cutoff = currentDate.minusDays(retention.toLong() - 1)
        rootDir
            .listFiles { file -> file.extension == "json" }
            ?.forEach { file ->
                val day = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
                if (day != null && day.isBefore(cutoff)) {
                    runCatching { file.delete() }
                }
            }
    }

    private fun readFile(file: File): List<EventLogEntry> {
        if (!file.exists()) return emptyList()
        val contents = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<EventLogEntry>>(contents) }.getOrElse { emptyList() }
    }

    private fun writeFile(file: File, events: List<EventLogEntry>) {
        val encoded = json.encodeToString(events)
        file.writeText(encoded)
    }

    private fun fileForDay(day: LocalDate): File {
        return File(rootDir, "${day}.json")
    }
}

private fun EventLogEntry.localDate(): LocalDate {
    return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
}

