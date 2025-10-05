package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MemoryItem(
    val content: String,
    val expiresAt: Long? = null // null means FOREVER
)

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
        prefs.edit().putString(PERMANENT_MEMORY_KEY, updated).apply()
        Log.d(TAG, "Added permanent memory: $content")
    }

    fun addTemporaryMemory(context: Context, content: String, durationMinutes: Int, timeProvider: TimeProvider = SystemTimeProvider) {
        val prefs = getPreferences(context)
        val now = timeProvider.now()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(durationMinutes.toLong())
        val key = "${TEMPORARY_MEMORY_PREFIX}$now"
        
        // Store as "content|expiresAt"
        prefs.edit().putString(key, "$content|$expiresAt").apply()
        Log.d(TAG, "Added temporary memory for $durationMinutes minutes: $content")
    }

    fun setPermanentMemory(context: Context, content: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(PERMANENT_MEMORY_KEY, content).apply()
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
        getPreferences(context).edit().putString(MEMORY_RULES_KEY, rules).apply()
        Log.d(TAG, "Updated memory rules (${rules.length} chars)")
    }

    data class TemporaryGroupByDate(
        val expirationDateKey: String,
        val items: List<String>
    )

    fun getActiveTemporaryGroupsByDate(
        context: Context,
        timeProvider: TimeProvider = SystemTimeProvider
    ): List<TemporaryGroupByDate> {
        val prefs = getPreferences(context)
        val currentTime = timeProvider.now()
        val editor = prefs.edit()
        val groups = mutableMapOf<String, MutableList<String>>()

        for ((key, value) in prefs.all) {
            if (key.startsWith(TEMPORARY_MEMORY_PREFIX) && value is String) {
                val parts = value.split("|")
                if (parts.size == 2) {
                    val content = parts[0]
                    val expiresAt = parts[1].toLongOrNull()
                    if (expiresAt != null) {
                        if (currentTime < expiresAt) {
                            val dateKey = LocalDate.ofInstant(
                                Instant.ofEpochMilli(expiresAt),
                                ZoneId.systemDefault()
                            ).toString() // yyyy-MM-dd
                            val list = groups.getOrPut(dateKey) { mutableListOf() }
                            list.add(content)
                        } else {
                            editor.remove(key)
                            Log.d(TAG, "Removed expired temp memory while grouping: $content")
                        }
                    }
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
        val currentTime = timeProvider.now()
        val memories = mutableListOf<String>()

        // Add permanent memories
        val permanentMemory = prefs.getString(PERMANENT_MEMORY_KEY, "")
        if (!permanentMemory.isNullOrEmpty()) {
            memories.add(permanentMemory)
        }

        // Add valid temporary memories and clean up expired ones
        val editor = prefs.edit()
        val allPrefs = prefs.all
        
        for ((key, value) in allPrefs) {
            if (key.startsWith(TEMPORARY_MEMORY_PREFIX) && value is String) {
                val parts = value.split("|")
                if (parts.size == 2) {
                    val content = parts[0]
                    val expiresAt = parts[1].toLongOrNull()
                    
                    if (expiresAt != null) {
                        if (currentTime < expiresAt) {
                            // Still valid
                            memories.add(content)
                        } else {
                            // Expired, remove it
                            editor.remove(key)
                            Log.d(TAG, "Removed expired memory: $content")
                        }
                    }
                }
            }
        }
        
        editor.apply()
        
        return memories.joinToString("\n")
    }

    fun clearAllMemories(context: Context) {
        getPreferences(context).edit().clear().apply()
        Log.d(TAG, "Cleared all memories")
    }
} 