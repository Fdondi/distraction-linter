package com.example.timelinter

import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global store for the AI (API) conversation history so the UI can observe it.
 * This maintains a persistent log across sessions with separators.
 */
object ConversationLogStore {
    private val _apiHistory = MutableStateFlow<List<Content>>(emptyList())
    val apiHistory: StateFlow<List<Content>> = _apiHistory.asStateFlow()

    private val _aiMemory = MutableStateFlow("")
    val aiMemory: StateFlow<String> = _aiMemory.asStateFlow()

    fun setApiHistory(history: List<Content>) {
        // Publish a snapshot (copy) to avoid accidental external mutation
        _apiHistory.value = history.toList()
    }

    fun addSessionSeparator(appName: String) {
        val separator = content(role = "user") { 
            text("------ NEW CONVERSATION: $appName ------") 
        }
        _apiHistory.value = _apiHistory.value + separator
    }

    fun appendToHistory(newContent: List<Content>) {
        _apiHistory.value = _apiHistory.value + newContent
    }

    fun setMemory(memory: String) {
        _aiMemory.value = memory
    }
}


