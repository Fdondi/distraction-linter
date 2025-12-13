package com.timelinter.app

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class TokenBucketConfig(
    val context: Context,
    val maxThreshold: Duration,
    val maxOverfill: Duration = Duration.ZERO,
    val overfillDecayPerHour: Duration = Duration.ZERO
)

@OptIn(kotlin.time.ExperimentalTime::class)
class TokenBucket(private val context: Context, private val timeProvider: TimeProvider = SystemTimeProvider) {
    // The bucket owns its own state - store remaining time as Duration
    private var currentRemaining: Duration = Duration.ZERO
    private var lastUpdate: Instant = timeProvider.now()
    private var lastCategoryId: String? = null

    // Initialize with current settings
    init {
        val maxThreshold = SettingsManager.getMaxThreshold(context)
        val persistedRemaining = SettingsManager.getThresholdRemaining(context, maxThreshold)
        currentRemaining = persistedRemaining
    }

    fun getCurrentRemaining(): Duration = currentRemaining

    fun update(category: ResolvedCategory?): Duration {
        val resolvedCategory = category ?: ResolvedCategory(
            id = AppCategoryIds.DEFAULT,
            label = "Default",
            minutesChangePerMinute = null,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = true
        )
        val config = getCurrentConfig()
        val now = timeProvider.now()

        // Calculate delta based on current state transition
        // If state changed, reset the time tracking to avoid counting time from previous state
        val delta = if (lastCategoryId != resolvedCategory.id) {
            // State changed - only count time from now, not from last update
            // This prevents deducting time that was spent in a different state
            Duration.ZERO
        } else {
            // Same state - count time since last update
            now - lastUpdate
        }
        
        // Update tracking
        lastCategoryId = resolvedCategory.id
        lastUpdate = now
        
        val newRemaining = when {
            resolvedCategory.minutesChangePerMinute == null ->
                updateNeutral(currentRemaining, delta, config)
            resolvedCategory.minutesChangePerMinute > 0f ->
                applyDirectChange(currentRemaining, delta, resolvedCategory.minutesChangePerMinute, config, resolvedCategory.allowOverfill)
            resolvedCategory.minutesChangePerMinute < 0f ->
                applyDirectChange(currentRemaining, delta, resolvedCategory.minutesChangePerMinute, config, allowOverfill = false)
            else ->
                updateNeutral(currentRemaining, delta, config)
        }
        val clampedRemaining = clampWithOverfill(newRemaining, config, resolvedCategory.allowOverfill)
        
        // Actually update the stored state
        currentRemaining = clampedRemaining
        
        return clampedRemaining
    }

    private fun getCurrentConfig(): TokenBucketConfig {
        return TokenBucketConfig(
            context = context,
            maxThreshold = SettingsManager.getMaxThreshold(context),
            maxOverfill = SettingsManager.getMaxOverfill(context),
            overfillDecayPerHour = SettingsManager.getOverfillDecayPerHour(context)
        )
    }

    private fun clampWithOverfill(value: Duration, config: TokenBucketConfig, allowOverfill: Boolean): Duration {
        val maxWithOverfill = if (allowOverfill) config.maxThreshold + config.maxOverfill else config.maxThreshold
        return when {
            value < Duration.ZERO -> Duration.ZERO
            value > maxWithOverfill -> maxWithOverfill
            else -> value
        }
    }

    private fun updateNeutral(currentRemaining: Duration, delta: Duration, config: TokenBucketConfig): Duration {
        if (delta <= Duration.ZERO) return currentRemaining

        // No refill; only decay overfill if any
        if (currentRemaining <= config.maxThreshold) {
            return currentRemaining
        }

        val overfillDecayPerHour = config.overfillDecayPerHour
        if (overfillDecayPerHour <= Duration.ZERO) return currentRemaining

        val overfillAmount = currentRemaining - config.maxThreshold
        val logDecayRate = Math.log(1 - overfillDecayPerHour.inWholeSeconds.toDouble() / 3600.0)
        val hoursElapsed = delta.inWholeSeconds.toDouble() / 3600.0
        return config.maxThreshold + overfillAmount * Math.exp(logDecayRate * hoursElapsed)
    }

    private fun applyDirectChange(
        currentRemaining: Duration,
        delta: Duration,
        rateMinutesPerMinute: Float,
        config: TokenBucketConfig,
        allowOverfill: Boolean
    ): Duration {
        if (delta <= Duration.ZERO) return currentRemaining
        val minutesElapsed = delta.inWholeSeconds.toDouble() / 60.0
        val changeMinutes = rateMinutesPerMinute * minutesElapsed
        val updated = currentRemaining + changeMinutes.minutes
        val cap = if (allowOverfill) config.maxThreshold + config.maxOverfill else config.maxThreshold
        return updated.coerceIn(Duration.ZERO, cap)
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


