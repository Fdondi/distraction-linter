package com.timelinter.app

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class TokenBucketConfig(
    val context: Context,
    val maxThreshold: Duration,
    val maxOverfill: Duration = Duration.ZERO,
    val overfillDecayPerHour: Duration = Duration.ZERO,
    val replenishRateFraction: Float = 0f,
    val neutralFillRateMultiplier: Float = 1f
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
        val persistedLast = SettingsManager.getThresholdLastUpdated(context, timeProvider.now())
        // Guard against clock skew; never allow a future last-update to produce negative deltas.
        lastUpdate = minOf(persistedLast, timeProvider.now())
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
        val delta = if (lastCategoryId == resolvedCategory.id || resolvedCategory.usesNeutralTimers) {
            // When staying in the same category, or when switching into a neutral/default category,
            // count the elapsed time so replenishment can occur after idle periods.
            now - lastUpdate
        } else {
            // State changed to a non-neutral category - avoid counting time spent in the previous state.
            Duration.ZERO
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
        
        // Actually update the stored state and persist so crashes/restarts recover correctly.
        currentRemaining = clampedRemaining
        persistState(now, clampedRemaining)
        
        return clampedRemaining
    }

    private fun getCurrentConfig(): TokenBucketConfig {
        return TokenBucketConfig(
            context = context,
            maxThreshold = SettingsManager.getMaxThreshold(context),
            maxOverfill = SettingsManager.getMaxOverfill(context),
            overfillDecayPerHour = SettingsManager.getOverfillDecayPerHour(context),
            replenishRateFraction = SettingsManager.getReplenishRateFraction(context),
            neutralFillRateMultiplier = SettingsManager.getNeutralAppFillRateMultiplier(context)
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

        // First decay any overfill
        if (currentRemaining > config.maxThreshold) {
            val overfillDecayPerHour = config.overfillDecayPerHour
            if (overfillDecayPerHour <= Duration.ZERO) return currentRemaining

            val overfillAmount = currentRemaining - config.maxThreshold
            val logDecayRate = Math.log(1 - overfillDecayPerHour.inWholeSeconds.toDouble() / 3600.0)
            val hoursElapsed = delta.inWholeSeconds.toDouble() / 3600.0
            return config.maxThreshold + overfillAmount * Math.exp(logDecayRate * hoursElapsed)
        }

        val baseRate = config.replenishRateFraction.coerceAtLeast(0f).toDouble()
        val neutralMultiplier = config.neutralFillRateMultiplier.coerceAtLeast(0f).toDouble()
        if (baseRate == 0.0 || neutralMultiplier == 0.0) return currentRemaining

        val minutesElapsed = delta.inWholeSeconds.toDouble() / 60.0
        val changeMinutes = baseRate * neutralMultiplier * minutesElapsed
        if (changeMinutes <= 0.0) return currentRemaining

        val updated = currentRemaining + changeMinutes.minutes
        return updated.coerceAtMost(config.maxThreshold)
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
        persistState(timeProvider.now(), currentRemaining)
    }

    private fun persistState(at: Instant, remaining: Duration) {
        SettingsManager.setThresholdRemaining(context, remaining)
        SettingsManager.setThresholdLastUpdated(context, at)
    }
}

object TriggerDecider {
    fun shouldTrigger(isWasteful: Boolean, isAllowed: Boolean, remainingTime: Duration): Boolean {
        return isWasteful && !isAllowed && remainingTime <= Duration.ZERO
    }
}


