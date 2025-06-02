package com.example.timelinter

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class InteractionState {
    OBSERVING,           // Step 0-1: Waiting for threshold to be exceeded
    CONVERSATION_ACTIVE, // Step 2-7: In active conversation with user
    WAITING_FOR_RESPONSE // Step 5b-6: Waiting for user response with timeout
}

class InteractionStateManager(private val context: Context) {
    private val TAG = "InteractionStateManager"
    
    @Volatile
    private var currentState = InteractionState.OBSERVING
    
    private val lastStateChangeTime = AtomicLong(System.currentTimeMillis())
    private val responseTimeoutTime = AtomicLong(0)
    private val allowedUntilTime = AtomicLong(0)
    
    // App-specific allow times
    private val appAllowTimes = mutableMapOf<String, Long>()
    
    fun getCurrentState(): InteractionState = currentState
    
    fun isInObservingState(): Boolean = currentState == InteractionState.OBSERVING
    
    fun isInConversationState(): Boolean = currentState == InteractionState.CONVERSATION_ACTIVE
    
    fun isWaitingForResponse(): Boolean = currentState == InteractionState.WAITING_FOR_RESPONSE
    
    fun shouldObserve(): Boolean {
        if (currentState != InteractionState.OBSERVING) return false
        
        val observeTimerMinutes = SettingsManager.getObserveTimerMinutes(context)
        val timeSinceLastChange = System.currentTimeMillis() - lastStateChangeTime.get()
        val observeTimerMs = TimeUnit.MINUTES.toMillis(observeTimerMinutes.toLong())
        
        return timeSinceLastChange >= observeTimerMs
    }
    
    fun isResponseTimedOut(): Boolean {
        if (currentState != InteractionState.WAITING_FOR_RESPONSE) return false
        
        val timeoutTime = responseTimeoutTime.get()
        return timeoutTime > 0 && System.currentTimeMillis() >= timeoutTime
    }
    
    fun isAllowed(appName: String? = null): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Check global allow
        if (currentTime < allowedUntilTime.get()) {
            Log.d(TAG, "Global allow active until ${java.util.Date(allowedUntilTime.get())}")
            return true
        }
        
        // Check app-specific allow
        if (appName != null) {
            val appAllowTime = appAllowTimes[appName] ?: 0
            if (currentTime < appAllowTime) {
                Log.d(TAG, "App '$appName' allowed until ${java.util.Date(appAllowTime)}")
                return true
            }
        }
        
        return false
    }
    
    fun startConversation() {
        Log.d(TAG, "Starting conversation (threshold exceeded)")
        currentState = InteractionState.CONVERSATION_ACTIVE
        lastStateChangeTime.set(System.currentTimeMillis())
    }
    
    fun startWaitingForResponse() {
        Log.d(TAG, "Waiting for user response")
        currentState = InteractionState.WAITING_FOR_RESPONSE
        
        val responseTimerMinutes = SettingsManager.getResponseTimerMinutes(context)
        val timeoutMs = TimeUnit.MINUTES.toMillis(responseTimerMinutes.toLong())
        responseTimeoutTime.set(System.currentTimeMillis() + timeoutMs)
        
        Log.d(TAG, "Response timeout set for ${java.util.Date(responseTimeoutTime.get())}")
    }
    
    fun resetToObserving() {
        Log.d(TAG, "Resetting to observing state")
        currentState = InteractionState.OBSERVING
        lastStateChangeTime.set(System.currentTimeMillis())
        responseTimeoutTime.set(0)
    }
    
    fun applyAllowCommand(allowCommand: ToolCommand.Allow) {
        val allowMs = TimeUnit.MINUTES.toMillis(allowCommand.minutes.toLong())
        val allowUntil = System.currentTimeMillis() + allowMs
        
        if (allowCommand.app != null) {
            // App-specific allow
            appAllowTimes[allowCommand.app] = allowUntil
            Log.i(TAG, "Applied ALLOW for '${allowCommand.app}' until ${java.util.Date(allowUntil)}")
        } else {
            // Global allow for all wasteful apps
            allowedUntilTime.set(allowUntil)
            Log.i(TAG, "Applied global ALLOW until ${java.util.Date(allowUntil)}")
        }
        
        // Reset conversation state
        resetToObserving()
    }
    
    fun continueConversation() {
        Log.d(TAG, "Continuing conversation")
        currentState = InteractionState.CONVERSATION_ACTIVE
        responseTimeoutTime.set(0)
    }
    
    fun getTimeSinceLastStateChange(): Long {
        return System.currentTimeMillis() - lastStateChangeTime.get()
    }
    
    fun getTimeUntilResponseTimeout(): Long {
        if (currentState != InteractionState.WAITING_FOR_RESPONSE) return 0
        val timeoutTime = responseTimeoutTime.get()
        if (timeoutTime == 0L) return 0
        return maxOf(0, timeoutTime - System.currentTimeMillis())
    }
    
    fun cleanupExpiredAllows() {
        val currentTime = System.currentTimeMillis()
        
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