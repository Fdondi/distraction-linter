package com.example.timelinter

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

enum class InteractionState {
    OBSERVING,           // Step 0-1: Waiting for threshold to be exceeded
    CONVERSATION_ACTIVE, // Step 2-7: In active conversation with user
    WAITING_FOR_RESPONSE // Step 5b-6: Waiting for user response with timeout
}

class InteractionStateManager(
    private val context: Context,
    private val timeProvider: TimeProvider = SystemTimeProvider
) {
    private val TAG = "InteractionStateManager"
    
    @Volatile
    private var currentState = InteractionState.OBSERVING
    
    private var lastStateChangeTime: Instant = timeProvider.now()
    private var responseTimeoutTime: Instant? = null
    private var allowedUntilTime: Instant? = null

    // App-specific allow times
    private val appAllowTimes = mutableMapOf<String, Instant>()
    
    fun getCurrentState(): InteractionState = currentState
    
    fun isInObservingState(): Boolean = currentState == InteractionState.OBSERVING
    
    fun isInConversationState(): Boolean = currentState == InteractionState.CONVERSATION_ACTIVE
    
    fun isWaitingForResponse(): Boolean = currentState == InteractionState.WAITING_FOR_RESPONSE
    
    fun shouldObserve(): Boolean {
        if (currentState != InteractionState.OBSERVING) return false
        
        val observeTimer = SettingsManager.getObserveTimer(context)
        val timeSinceLastChange = timeProvider.now() - lastStateChangeTime
        
        return timeSinceLastChange >= observeTimer
    }

    fun isResponseTimedOut(): Boolean {
        if (currentState != InteractionState.WAITING_FOR_RESPONSE) return false
        
        val timeoutTime = responseTimeoutTime!!
        return timeProvider.now() >= timeoutTime
    }
    
    fun isAllowed(appName: String? = null): Boolean {
        val currentTime = timeProvider.now()
        
        // Check global allow
        val globalAllowedUntil = allowedUntilTime
        if (globalAllowedUntil != null && currentTime < globalAllowedUntil) {
            Log.d(TAG, "Global allow active")
            return true
        }
        
        // Check app-specific allow
        if (appName != null) {
            val appAllowTime = appAllowTimes[appName]
            if (appAllowTime != null && currentTime < appAllowTime) {
                Log.d(TAG, "App '$appName' allowed")
                return true
            }
        }
        
        return false
    }
    
    fun startConversation() {
        Log.d(TAG, "Starting conversation (threshold exceeded)")
        currentState = InteractionState.CONVERSATION_ACTIVE
        lastStateChangeTime = timeProvider.now()
        EventLogStore.logStateChange(currentState)
    }
    
    fun startWaitingForResponse() {
        Log.d(TAG, "Waiting for user response")
        currentState = InteractionState.WAITING_FOR_RESPONSE
        
        val responseTimerMinutes = SettingsManager.getResponseTimerMinutes(context)
        responseTimeoutTime = timeProvider.now() + responseTimerMinutes.minutes

        Log.d(TAG, "Response timeout set for $responseTimerMinutes minutes from now")
        EventLogStore.logStateChange(currentState)
    }
    
    fun resetToObserving() {
        Log.d(TAG, "Resetting to observing state")
        currentState = InteractionState.OBSERVING
        lastStateChangeTime.set(timeProvider.now())
        responseTimeoutTime.set(0)
        EventLogStore.logStateChange(currentState)
    }
    
    fun applyAllowCommand(allowCommand: ToolCommand.Allow) {
        val allowUntil = timeProvider.now() + allowCommand.duration

        if (allowCommand.app != null) {
            // App-specific allow
            appAllowTimes[allowCommand.app] = allowUntil
            Log.i(TAG, "Applied ALLOW for '${allowCommand.app}' for ${allowCommand.duration}")
        } else {
            // Global allow for all wasteful apps
            allowedUntilTime = allowUntil
            Log.i(TAG, "Applied global ALLOW for ${allowCommand.duration}")
        }

        // Reset conversation state
        resetToObserving()
    }
    
    fun continueConversation() {
        Log.d(TAG, "Continuing conversation")
        currentState = InteractionState.CONVERSATION_ACTIVE
        responseTimeoutTime = null
        EventLogStore.logStateChange(currentState)
    }
    
    fun getTimeSinceLastStateChange(): Duration {
        return timeProvider.now() - lastStateChangeTime
    }

    fun getTimeUntilResponseTimeout(): Duration {
        if (currentState != InteractionState.WAITING_FOR_RESPONSE) return Duration.ZERO
        val timeoutTime = responseTimeoutTime!!
        val now = timeProvider.now()
        if (timeoutTime <= now) return Duration.ZERO
        return timeoutTime - now
    }
    
    fun cleanupExpiredAllows() {
        val currentTime = timeProvider.now()
        
        // Clean up expired app-specific allows
        val iterator = appAllowTimes.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime >= entry.value) {
                Log.d(TAG, "Expired allow for app '${entry.key}'")
                iterator.remove()
            }
        }
    }
} 