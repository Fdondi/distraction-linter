@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import android.content.Context
import android.util.Log
import kotlin.time.ExperimentalTime
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
        val activeAllows = TemporaryAllowStore.getActiveAllows(context, timeProvider)
        if (activeAllows.any { it.appName == null }) {
            Log.d(tag, "Global allow active")
            return true
        }
        if (appName != null && activeAllows.any { it.appName == appName }) {
            Log.d(tag, "App '$appName' allowed")
            return true
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
        TemporaryAllowStore.upsertAllow(context, allowCommand.app, allowCommand.duration, timeProvider)
        Log.i(
            tag,
            "Applied ALLOW for ${allowCommand.app ?: "all wasteful apps"} for ${allowCommand.duration}"
        )
        resetToObserving()
    }

    fun continueConversation() {
        Log.d(tag, "Continuing conversation")
        currentState = InteractionState.ConversationActive
        EventLogStore.logStateChange(getStateName())
    }

    fun cleanupExpiredAllows() {
        TemporaryAllowStore.cleanupExpired(context, timeProvider)
    }
}
