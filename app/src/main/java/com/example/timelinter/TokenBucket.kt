package com.example.timelinter

import java.util.concurrent.TimeUnit

data class TokenBucketConfig(
    val maxThresholdMs: Long,
    val replenishIntervalMs: Long,
    val replenishAmountMs: Long
)

// Result class to return both updated values
data class TokenBucketUpdateResult(
    val newRemainingMs: Long,
    val newAccumulatedNonWastefulMs: Long
)

object TokenBucket {
    fun update(
        previousRemainingMs: Long,
        previousAccumulatedNonWastefulMs: Long, // New parameter
        lastUpdateTimeMs: Long,
        nowMs: Long,
        isCurrentlyWasteful: Boolean,
        config: TokenBucketConfig
    ): TokenBucketUpdateResult { // Return type changed
        val delta = nowMs - lastUpdateTimeMs
        if (delta <= 0L) {
            return TokenBucketUpdateResult(
                clamp(previousRemainingMs, config.maxThresholdMs),
                previousAccumulatedNonWastefulMs
            )
        }

        var remaining = previousRemainingMs
        var accumulated = previousAccumulatedNonWastefulMs

        if (isCurrentlyWasteful) {
            remaining -= delta
            // accumulatedNonWastefulMs is not reset here; prior non-wasteful time still counts.
        } else { // Not wasteful
            accumulated += delta
            if (config.replenishIntervalMs > 0L && config.replenishAmountMs > 0L) {
                val replenishmentsPossible = accumulated / config.replenishIntervalMs
                if (replenishmentsPossible > 0) {
                    remaining += replenishmentsPossible * config.replenishAmountMs
                    accumulated %= config.replenishIntervalMs // Keep the remainder
                }
            }
        }

        return TokenBucketUpdateResult(
            clamp(remaining, config.maxThresholdMs),
            accumulated
        )
    }

    private fun clamp(value: Long, max: Long): Long {
        return when {
            value < 0L -> 0L
            value > max -> max
            else -> value
        }
    }
}

object TriggerDecider {
    fun shouldTrigger(isWasteful: Boolean, isAllowed: Boolean, remainingMs: Long): Boolean {
        return isWasteful && !isAllowed && remainingMs <= 0L
    }
}
