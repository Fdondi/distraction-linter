package com.timelinter.app

import kotlin.time.Duration.Companion.minutes


object AllowIntentDetector {
    private val minutePattern = Regex("(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b", RegexOption.IGNORE_CASE)

    private val intentPatterns: List<Regex> = listOf(
        Regex("\\btake (?:the )?time you need\\b", RegexOption.IGNORE_CASE),
        Regex("\\btake your time\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno rush\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhenever you're ready\\b", RegexOption.IGNORE_CASE),
        Regex("\\bI'll be here\\b", RegexOption.IGNORE_CASE),
        Regex("\\bI'?ll wait\\b", RegexOption.IGNORE_CASE),
        Regex("\\benjoy your (?:break|shower|meal|lunch|dinner|walk|nap)\\b", RegexOption.IGNORE_CASE),
        Regex("\\btake a break\\b", RegexOption.IGNORE_CASE),
        Regex("\\bgo ahead\\b", RegexOption.IGNORE_CASE)
    )

    private val heuristicDurations: List<Pair<Regex, Int>> = listOf(
        Regex("shower", RegexOption.IGNORE_CASE) to 20,
        Regex("lunch|dinner|meal|eat", RegexOption.IGNORE_CASE) to 30,
        Regex("nap", RegexOption.IGNORE_CASE) to 30,
        Regex("walk", RegexOption.IGNORE_CASE) to 20,
        Regex("break", RegexOption.IGNORE_CASE) to 15
    )

    fun inferAllow(modelMessage: String, currentAppReadableName: String?): ToolCommand.Allow? {
        if (modelMessage.isBlank()) return null

        val hasIntent = intentPatterns.any { it.containsMatchIn(modelMessage) }
        if (!hasIntent) return null

        val explicitMinutes = minutePattern.find(modelMessage)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val minutes = explicitMinutes ?: run {
            heuristicDurations.firstOrNull { it.first.containsMatchIn(modelMessage) }?.second ?: 10
        }

        val sanitizedMinutes = minutes.coerceIn(1, 240).minutes

        val app = currentAppReadableName?.takeIf { it.isNotBlank() }
        return ToolCommand.Allow(duration = sanitizedMinutes, app = app)
    }
}


