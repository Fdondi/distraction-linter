package com.timelinter.app

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Instant

data class TokenBucketConfig(
    val context: Context,
    val maxThreshold: Duration,
    val replenishInterval: Duration,
    val replenishAmount: Duration,
    val maxOverfill: Duration = Duration.ZERO,
    val overfillDecayPerHour: Duration = Duration.ZERO,
    val fillRateMultiplier: Float = 1.0f
)
enum class AppState {
    WASTEFUL {
        override fun updateRemainingTime(
            currentRemaining: Duration,
            delta: Duration,
            config: TokenBucketConfig
        ): Duration {
            // Consume time - subtract delta from remaining
            return maxOf(Duration.ZERO, currentRemaining - delta)
        }
    },
    GOOD {
        override fun updateRemainingTime(
            currentRemaining: Duration,
            delta: Duration,
            config: TokenBucketConfig
        ): Duration {
            // Apply replenishment using intervals
            if (config.replenishInterval <= Duration.ZERO || config.replenishAmount <= Duration.ZERO || delta <= Duration.ZERO) {
                return currentRemaining
            }
            val fillRateMultiplier = SettingsManager.getGoodAppFillRateMultiplier(config.context)
            if (fillRateMultiplier <= 0.0f)
                return currentRemaining
            val fillDuration = delta * fillRateMultiplier.toDouble()
            val maxTotal = config.maxThreshold + config.maxOverfill
            val replenishmentRate = fillDuration / config.replenishInterval
            val actualReplenishment = config.replenishAmount * replenishmentRate
            return minOf(maxTotal, currentRemaining + actualReplenishment)
        }
    },
    NEUTRAL {
        override fun updateRemainingTime(
            currentRemaining: Duration,
            delta: Duration,
            config: TokenBucketConfig
        ): Duration {
            // If below max threshold, refill using normal replenishment with neutral multiplier
            if (currentRemaining < config.maxThreshold) {
                // Apply normal replenishment using intervals with neutral app multiplier
                if (config.replenishInterval <= Duration.ZERO || config.replenishAmount <= Duration.ZERO || delta <= Duration.ZERO) {
                    return currentRemaining
                }
                val neutralMultiplier = SettingsManager.getNeutralAppFillRateMultiplier(config.context)
                if (neutralMultiplier <= 0.0f) {
                    return currentRemaining
                }
                val fillDuration = delta * neutralMultiplier.toDouble()
                val replenishmentRate = fillDuration / config.replenishInterval
                val actualReplenishment = config.replenishAmount * replenishmentRate
                val refilled = minOf(config.maxThreshold, currentRemaining + actualReplenishment)
                return refilled
            }
            
            // If at or above max threshold, decay overfill if present
            if (currentRemaining <= config.maxThreshold) {
                return currentRemaining
            }

            val overfillDecayPerHour = getOverfillDecayPerHour(config.context)
            if(overfillDecayPerHour <= Duration.ZERO)
                return currentRemaining

            // decay with continuous compounding
            val overfillAmount = currentRemaining - config.maxThreshold
            val LogDecayRate = Math.log(1 - overfillDecayPerHour.inWholeSeconds.toDouble() / 3600.0)
            val hoursElapsed = delta.inWholeSeconds.toDouble() / 3600.0
            return config.maxThreshold + overfillAmount * Math.exp(LogDecayRate * hoursElapsed)
        }

        fun getMaxTime(context: Context): Duration = SettingsManager.getMaxOverfill(context)
        fun getOverfillDecayPerHour(context: Context): Duration = SettingsManager.getOverfillDecayPerHour(context)
    };

    abstract fun updateRemainingTime(
        currentRemaining: Duration,
        delta: Duration,
        config: TokenBucketConfig
    ): Duration
}

@OptIn(kotlin.time.ExperimentalTime::class)
class TokenBucket(private val context: Context, private val timeProvider: TimeProvider = SystemTimeProvider) {
    // The bucket owns its own state - store remaining time as Duration
    private var currentRemaining: Duration = Duration.ZERO
    private var lastUpdate: Instant = timeProvider.now()
    private var lastAppState: AppState? = null

    // Initialize with current settings
    init {
        val maxThreshold = SettingsManager.getMaxThreshold(context)
        val persistedRemaining = SettingsManager.getThresholdRemaining(context, maxThreshold)
        currentRemaining = persistedRemaining
    }

    fun getCurrentRemaining(): Duration = currentRemaining

    fun update(appState: AppState): Duration {
        val config = getCurrentConfig()
        val now = timeProvider.now()

        // Calculate delta based on current state transition
        // If state changed, reset the time tracking to avoid counting time from previous state
        val delta = if (lastAppState != appState) {
            // State changed - only count time from now, not from last update
            // This prevents deducting time that was spent in a different state
            Duration.ZERO
        } else {
            // Same state - count time since last update
            now - lastUpdate
        }
        
        // Update tracking
        lastAppState = appState
        lastUpdate = now
        
        // The appState's updateRemainingTime will handle the logic:
        // - WASTEFUL: deducts delta
        // - GOOD: adds time (with multiplier)
        // - NEUTRAL: refills when below max (with neutral multiplier), decays overfill when above max
        val newRemaining = appState.updateRemainingTime(currentRemaining, delta, config)
        val clampedRemaining = clampWithOverfill(newRemaining, config)
        
        // Actually update the stored state
        currentRemaining = clampedRemaining
        
        return clampedRemaining
    }

    private fun getCurrentConfig(): TokenBucketConfig {
        return TokenBucketConfig(
            context = context,
            maxThreshold = SettingsManager.getMaxThreshold(context),
            replenishInterval = SettingsManager.getReplenishInterval(context),
            replenishAmount = SettingsManager.getReplenishAmount(context),
            maxOverfill = SettingsManager.getMaxOverfill(context),
            overfillDecayPerHour = SettingsManager.getOverfillDecayPerHour(context),
            fillRateMultiplier = 1.0f
        )
    }

    private fun clampWithOverfill(value: Duration, config: TokenBucketConfig): Duration {
        val maxWithOverfill = config.maxThreshold + config.maxOverfill
        return when {
            value < Duration.ZERO -> Duration.ZERO
            value > maxWithOverfill -> maxWithOverfill
            else -> value
        }
    }

    fun persistCurrentState() {
        SettingsManager.setThresholdRemaining(context, currentRemaining)
    }
}

object TriggerDecider {
    fun shouldTrigger(isWasteful: Boolean, isAllowed: Boolean, remainingTime: Duration): Boolean {
        return isWasteful && !isAllowed && remainingTime <= Duration.ZERO
    }
}


