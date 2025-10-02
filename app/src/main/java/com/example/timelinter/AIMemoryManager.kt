package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.TimeUnit

data class MemoryItem(
    val content: String,
    val expiresAt: Long? = null // null means FOREVER
)

object AIMemoryManager {
    private const val PREF_NAME = "ai_memory"
    private const val PERMANENT_MEMORY_KEY = "permanent_memory"
    private const val TEMPORARY_MEMORY_PREFIX = "temp_memory_"
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