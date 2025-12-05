@file:OptIn(ExperimentalTime::class)

package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import androidx.core.content.edit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class MemoryItem(
    val content: String,
    val createdAt: Instant,
    val expiresAt: Instant? = null // null means permanent memory
    
) {
    fun isExpiredAt(now: Instant): Boolean {
        return expiresAt != null && now >= expiresAt
    }
}


object AIMemoryManager {
    private const val PREF_NAME = "ai_memory"
    private const val PERMANENT_MEMORY_KEY = "permanent_memory"
    private const val TEMPORARY_MEMORY_PREFIX = "temp_memory_"
    private const val MEMORY_RULES_KEY = "memory_rules"
    private const val TAG = "AIMemoryManager"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun addPermanentMemory(context: Context, content: String) {
        val prefs = getPreferences(context)
        val existing = prefs.getString(PERMANENT_MEMORY_KEY, "") ?: ""
        val updated = if (existing.isEmpty()) {
            content
        } else {
            "$existing\n$content"
        }
        prefs.edit { putString(PERMANENT_MEMORY_KEY, updated) }
        Log.d(TAG, "Added permanent memory: $content")
    }

    fun addTemporaryMemory(context: Context, content: String, duration: Duration, timeProvider: TimeProvider = SystemTimeProvider) {
        val prefs = getPreferences(context)
        val expiresAt = timeProvider.now() + duration
        val key = "${TEMPORARY_MEMORY_PREFIX}${timeProvider.now().epochSeconds}"

        val memoryItem = MemoryItem(content, timeProvider.now(), expiresAt)
        val serialized = Json.encodeToString(memoryItem)
        prefs.edit { putString(key, serialized) }
        Log.d(TAG, "Added temporary memory for $duration: $content")
    }

    fun setPermanentMemory(context: Context, content: String) {
        val prefs = getPreferences(context)
        prefs.edit { putString(PERMANENT_MEMORY_KEY, content) }
        Log.d(TAG, "Set permanent memory (replaced): ${content.take(64)}")
    }

    fun getPermanentMemory(context: Context): String {
        return getPreferences(context).getString(PERMANENT_MEMORY_KEY, "") ?: ""
    }

    fun getMemoryRules(context: Context): String {
        val prefs = getPreferences(context)
        val existing = prefs.getString(MEMORY_RULES_KEY, null)
        if (existing != null) return existing
        // Fallback to default rules from raw resource
        return try {
            context.resources.openRawResource(R.raw.ai_memory_rules)
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Default memory rules not found, returning empty string", e)
            ""
        }
    }

    fun setMemoryRules(context: Context, rules: String) {
        getPreferences(context).edit { putString(MEMORY_RULES_KEY, rules) }
        Log.d(TAG, "Updated memory rules (${rules.length} chars)")
    }

    data class TemporaryGroupByDate(
        val expirationDateKey: Instant,
        val items: List<String>
    )

    fun getActiveTemporaryGroupsByDate(
        context: Context,
        timeProvider: TimeProvider = SystemTimeProvider
    ): List<TemporaryGroupByDate> {
        val prefs = getPreferences(context)
        val editor = prefs.edit()
        val groups = mutableMapOf<Instant, MutableList<String>>()

        for ((key, value) in prefs.all) {
            if (key.startsWith(TEMPORARY_MEMORY_PREFIX) && value is String) {
                try {
                    val memoryItem = Json.decodeFromString<MemoryItem>(value)
                    if (!memoryItem.isExpiredAt(timeProvider.now())) {
                        val expirationDate = memoryItem.expiresAt ?: continue
                        val list = groups.getOrPut(expirationDate) { mutableListOf() }
                        list.add(memoryItem.content)
                    } else {
                        editor.remove(key)
                        Log.d(TAG, "Removed expired temp memory while grouping: ${memoryItem.content}")
                    }
                } catch (e: Exception) {
                    // If deserialization fails, remove the corrupted entry
                    editor.remove(key)
                    Log.w(TAG, "Removed corrupted memory entry while grouping: $key")
                }
            }
        }
        editor.apply()

        return groups.entries
            .sortedBy { it.key }
            .map { TemporaryGroupByDate(it.key, it.value.toList()) }
    }

    fun getAllMemories(context: Context, timeProvider: TimeProvider = SystemTimeProvider): String {
        val prefs = getPreferences(context)
        val memories = mutableListOf<String>()

        // Add permanent memories
        val permanentMemory = prefs.getString(PERMANENT_MEMORY_KEY, "")
        if (!permanentMemory.isNullOrEmpty()) {
            memories.add(permanentMemory)
        }

        // Add valid temporary memories and clean up expired ones
        prefs.edit {
            val allPrefs = prefs.all

            for ((key, value) in allPrefs) {
                if (key.startsWith(TEMPORARY_MEMORY_PREFIX) && value is String) {
                    try {
                        val memoryItem = Json.decodeFromString<MemoryItem>(value)
                        if (!memoryItem.isExpiredAt(timeProvider.now())) {
                            // Still valid
                            memories.add(memoryItem.content)
                        } else {
                            // Expired, remove it
                            remove(key)
                            Log.d(TAG, "Removed expired memory: ${memoryItem.content}")
                        }
                    } catch (e: Exception) {
                        // If deserialization fails, remove the corrupted entry
                        remove(key)
                        Log.w(TAG, "Removed corrupted memory entry: $key")
                    }
                }
            }

        }
        
        return memories.joinToString("\n")
    }

    fun clearAllMemories(context: Context) {
        getPreferences(context).edit { clear() }
        Log.d(TAG, "Cleared all memories")
    }
} 