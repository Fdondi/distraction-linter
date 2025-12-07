package com.timelinter.app

/**
 * Represents different types of AI tasks that can use different models
 */
enum class AITask(val displayName: String, val description: String) {
    FIRST_MESSAGE(
        displayName = "First Message",
        description = "Initial AI message when threshold is exceeded"
    ),
    USER_RESPONSE(
        displayName = "Response to User",
        description = "AI response to user's message"
    ),
    FOLLOWUP_NO_RESPONSE(
        displayName = "Follow-up (No Response)",
        description = "Follow-up message when user doesn't respond"
    ),
    SUMMARY(
        displayName = "Summary",
        description = "Summarize session and extract memories on reset"
    );

    companion object {
        fun fromString(name: String): AITask? {
            return entries.find { it.name == name }
        }
    }
}
