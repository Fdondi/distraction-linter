package com.example.timelinter

import java.util.concurrent.TimeUnit

data class TokenBucketConfig(
    val maxThresholdMs: Long,
    val replenishIntervalMs: Long,
    val replenishAmountMs: Long,
    // Good Apps configuration
    val maxOverfillMs: Long = 0L, // Maximum overfill beyond maxThresholdMs
    val overfillDecayPerHourMs: Long = 0L, // Overfill decay per hour
    val goodAppRewardIntervalMs: Long = 0L, // Interval for good app rewards
    val goodAppRewardAmountMs: Long = 0L // Amount rewarded per interval
)

// Result class to return both updated values
data class TokenBucketUpdateResult(
    val newRemainingMs: Long,
    val newAccumulatedNonWastefulMs: Long,
    val newGoodAppAccumulatedMs: Long = 0L
)

object TokenBucket {
    fun update(
        previousRemainingMs: Long,
        previousAccumulatedNonWastefulMs: Long,
        lastUpdateTimeMs: Long,
        nowMs: Long,
        isCurrentlyWasteful: Boolean,
        config: TokenBucketConfig,
        isCurrentlyGoodApp: Boolean = false,
        previousGoodAppAccumulatedMs: Long = 0L
    ): TokenBucketUpdateResult {
        val delta = nowMs - lastUpdateTimeMs
        if (delta <= 0L) {
            return TokenBucketUpdateResult(
                clampWithOverfill(previousRemainingMs, config),
                previousAccumulatedNonWastefulMs,
                previousGoodAppAccumulatedMs
            )
        }

        var remaining = previousRemainingMs
        var accumulated = previousAccumulatedNonWastefulMs
        var goodAppAccumulated = previousGoodAppAccumulatedMs

        if (isCurrentlyWasteful) {
            // Consume time from bucket (including any overfill)
            remaining -= delta
        } else {
            // Not wasteful - accumulate for normal replenishment (only applies when below max)
            accumulated += delta
            if (config.replenishIntervalMs > 0L && config.replenishAmountMs > 0L) {
                val replenishmentsPossible = accumulated / config.replenishIntervalMs
                if (replenishmentsPossible > 0) {
                    accumulated %= config.replenishIntervalMs
                    // Normal replenishment only helps when below max threshold
                    if (remaining < config.maxThresholdMs) {
                        remaining = minOf(
                            config.maxThresholdMs,
                            remaining + replenishmentsPossible * config.replenishAmountMs
                        )
                    }
                }
            }

            // If using a good app, give bonus rewards (can go above max)
            if (isCurrentlyGoodApp && config.goodAppRewardIntervalMs > 0L && config.goodAppRewardAmountMs > 0L) {
                goodAppAccumulated += delta
                val rewardsPossible = goodAppAccumulated / config.goodAppRewardIntervalMs
                if (rewardsPossible > 0) {
                    remaining += rewardsPossible * config.goodAppRewardAmountMs
                    goodAppAccumulated %= config.goodAppRewardIntervalMs
                }
            } else {
                // Overfill decays over time when NOT using a good app (gets smaller until used up)
                if (config.overfillDecayPerHourMs > 0L) {
                    val overfillAmount = maxOf(0L, remaining - config.maxThresholdMs)
                    if (overfillAmount > 0L) {
                        val hoursElapsed = delta.toDouble() / TimeUnit.HOURS.toMillis(1)
                        val decayAmount = (config.overfillDecayPerHourMs * hoursElapsed).toLong()
                        val actualDecay = minOf(decayAmount, overfillAmount)
                        remaining -= actualDecay
                    }
                }
            }
        }

        return TokenBucketUpdateResult(
            clampWithOverfill(remaining, config),
            accumulated,
            goodAppAccumulated
        )
    }

    private fun clampWithOverfill(value: Long, config: TokenBucketConfig): Long {
        val maxWithOverfill = config.maxThresholdMs + config.maxOverfillMs
        return when {
            value < 0L -> 0L
            value > maxWithOverfill -> maxWithOverfill
            else -> value
        }
    }
}

object TriggerDecider {
    fun shouldTrigger(isWasteful: Boolean, isAllowed: Boolean, remainingMs: Long): Boolean {
        return isWasteful && !isAllowed && remainingMs <= 0L
    }
}



