package com.timelinter.app

import androidx.core.app.Person

/**
 * Represents a chat message in the conversation.
 * Used for both UI display and internal conversation tracking.
 */
data class ChatMessage(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    // Person is only used for UI notifications, can be null for internal tracking
    val sender: Person? = null
) 
