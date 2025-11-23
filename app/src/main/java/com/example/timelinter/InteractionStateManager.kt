package com.example.timelinter

import android.content.Context
import android.util.Log
import kotlin.time.Duration
import kotlin.time.Instant

sealed class InteractionState {
    object Observing : InteractionState()
    object ConversationActive : InteractionState()
    data class WaitingForResponse(val timeout: Instant) : InteractionState()
}

class InteractionStateManager(
    private val context: Context,
    private val timeProvider: TimeProvider = SystemTimeProvider
) {
    private val tag = "InteractionStateManager"

    @Volatile
    private var currentState: InteractionState = InteractionState.Observing

    private var lastStateChangeTime: Instant = timeProvider.now()
    private var allowedUntilTime: Instant? = null

    // App-specific allow times
    private val appAllowTimes = mutableMapOf<String, Instant>()

    // Internal state access - kept for backward compatibility but should not be used by external code
    fun getCurrentState(): InteractionState = currentState

    // Minimal API for external consumers - abstracts away internal state representation
    fun isInObservingState(): Boolean = currentState is InteractionState.Observing

    fun isInConversationState(): Boolean = currentState is InteractionState.ConversationActive

    fun isWaitingForResponse(): Boolean = currentState is InteractionState.WaitingForResponse

    fun isResponseTimedOut(): Boolean {
        val state = currentState
        if (state !is InteractionState.WaitingForResponse) return false
        return timeProvider.now() >= state.timeout
    }

    /**
     * Returns a string representation of the current state for logging purposes.
     * This abstracts away the internal sealed class representation.
     */
    fun getStateName(): String {
        return when (currentState) {
            is InteractionState.Observing -> "Observing"
            is InteractionState.ConversationActive -> "ConversationActive"
            is InteractionState.WaitingForResponse -> "WaitingForResponse"
        }
    }

    fun isAllowed(appName: String? = null): Boolean {
        val currentTime = timeProvider.now()

        // Check global allow
        val globalAllowedUntil = allowedUntilTime
        if (globalAllowedUntil != null && currentTime < globalAllowedUntil) {
            Log.d(tag, "Global allow active")
            return true
        }

        // Check app-specific allow
        if (appName != null) {
            val appAllowTime = appAllowTimes[appName]
            if (appAllowTime != null && currentTime < appAllowTime) {
                Log.d(tag, "App '$appName' allowed")
                return true
            }
        }

        return false
    }

    fun startConversation() {
        Log.d(tag, "Starting conversation (threshold exceeded)")
        currentState = InteractionState.ConversationActive
        lastStateChangeTime = timeProvider.now()
        EventLogStore.logStateChange(getStateName())
    }

    fun startWaitingForResponse() {
        Log.d(tag, "Waiting for user response")

        val responseTimerMinutes = SettingsManager.getResponseTimer(context)
        val timeout = timeProvider.now() + responseTimerMinutes
        currentState = InteractionState.WaitingForResponse(timeout)

        Log.d(tag, "Response timeout set for $responseTimerMinutes minutes from now")
        EventLogStore.logStateChange(getStateName())
    }

    fun resetToObserving() {
        Log.d(tag, "Resetting to observing state")
        currentState = InteractionState.Observing
        lastStateChangeTime = timeProvider.now()
        EventLogStore.logStateChange(getStateName())
    }

    fun applyAllowCommand(allowCommand: ToolCommand.Allow) {
        val allowUntil = timeProvider.now() + allowCommand.duration

        if (allowCommand.app != null) {
            // App-specific allow
            appAllowTimes[allowCommand.app] = allowUntil
            Log.i(tag, "Applied ALLOW for '${allowCommand.app}' for ${allowCommand.duration}")
        } else {
            // Global allow for all wasteful apps
            allowedUntilTime = allowUntil
            Log.i(tag, "Applied global ALLOW for ${allowCommand.duration}")
        }

        // Reset conversation state
        resetToObserving()
    }

    fun continueConversation() {
        Log.d(tag, "Continuing conversation")
        currentState = InteractionState.ConversationActive
        EventLogStore.logStateChange(getStateName())
    }

    fun cleanupExpiredAllows() {
        val currentTime = timeProvider.now()

        // Clean up expired app-specific allows
        val iterator = appAllowTimes.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime >= entry.value) {
                Log.d(tag, "Expired allow for app '${entry.key}'")
                iterator.remove()
            }
        }
    }
}
