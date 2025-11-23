package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import androidx.core.content.edit

object SettingsManager {
    private const val PREF_NAME = "timelinter_settings"
    private const val OBSERVE_TIMER_MINUTES_KEY = "observe_timer_minutes"
    private const val RESPONSE_TIMER_MINUTES_KEY = "response_timer_minutes"
    
    // Default values
    private const val DEFAULT_OBSERVE_TIMER_MINUTES = 5
    private const val DEFAULT_RESPONSE_TIMER_MINUTES = 1

    // Threshold bucket settings
    private const val MAX_THRESHOLD_MINUTES_KEY = "max_threshold_minutes"
    private const val REPLENISH_INTERVAL_MINUTES_KEY = "replenish_interval_minutes" // How often (in minutes) to replenish
    private const val REPLENISH_AMOUNT_MINUTES_KEY = "replenish_amount_minutes"      // How many minutes to replenish each interval
    private const val THRESHOLD_REMAINING_MS_KEY = "threshold_remaining_ms"           // Internal token bucket storage (ms)

    // Defaults
    private const val DEFAULT_MAX_THRESHOLD_MINUTES = 5
    private const val DEFAULT_REPLENISH_INTERVAL_MINUTES = 10
    private const val DEFAULT_REPLENISH_AMOUNT_MINUTES = 1

    // Good Apps settings keys
    private const val MAX_OVERFILL_MINUTES_KEY = "max_overfill_minutes"
    private const val OVERFILL_DECAY_PER_HOUR_MINUTES_KEY = "overfill_decay_per_hour_minutes"
    private const val GOOD_APP_FILL_RATE_MULTIPLIER_KEY = "good_app_fill_rate_multiplier"
    private const val NEUTRAL_APP_FILL_RATE_MULTIPLIER_KEY = "neutral_app_fill_rate_multiplier"

    // Good Apps default values
    private const val DEFAULT_MAX_OVERFILL_MINUTES = 30
    private const val DEFAULT_OVERFILL_DECAY_PER_HOUR_MINUTES = 10
    private const val DEFAULT_GOOD_APP_FILL_RATE_MULTIPLIER = 2

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getObserveTimer(context: Context): Duration {
        return getPreferences(context).getInt(OBSERVE_TIMER_MINUTES_KEY, DEFAULT_OBSERVE_TIMER_MINUTES).minutes
    }
    fun setObserveTimer(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                OBSERVE_TIMER_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    fun getResponseTimer(context: Context): Duration {
        return getPreferences(context).getInt(RESPONSE_TIMER_MINUTES_KEY, DEFAULT_RESPONSE_TIMER_MINUTES).minutes
    }

    fun setResponseTimer(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                RESPONSE_TIMER_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    /* =========================  Threshold Settings  ========================= */

    fun getMaxThreshold(context: Context): Duration {
        return getPreferences(context).getInt(MAX_THRESHOLD_MINUTES_KEY, DEFAULT_MAX_THRESHOLD_MINUTES).minutes
    }

    fun setMaxThreshold(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                MAX_THRESHOLD_MINUTES_KEY,
                duration.toInt(DurationUnit.MINUTES)
            )
        }
    }

    fun getReplenishInterval(context: Context): Duration {
        return getPreferences(context).getInt(REPLENISH_INTERVAL_MINUTES_KEY, DEFAULT_REPLENISH_INTERVAL_MINUTES).minutes
    }

    fun setReplenishInterval(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                REPLENISH_INTERVAL_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    fun getReplenishAmount(context: Context): Duration {
        return getPreferences(context).getInt(REPLENISH_AMOUNT_MINUTES_KEY, DEFAULT_REPLENISH_AMOUNT_MINUTES).minutes
    }

    fun setReplenishAmount(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                REPLENISH_AMOUNT_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    /* =========================  Threshold Remaining (persistence)  ========================= */
    fun getThresholdRemaining(context: Context, defaultValue: Duration): Duration {
        return getPreferences(context).getLong(THRESHOLD_REMAINING_MS_KEY, defaultValue.inWholeMilliseconds).toDuration(DurationUnit.MILLISECONDS)
    }

    fun setThresholdRemaining(context: Context, value: Duration) {
        getPreferences(context).edit {
            putLong(
                THRESHOLD_REMAINING_MS_KEY,
                value.inWholeMilliseconds
            )
        }
    }

    /* =========================  Good Apps Settings  ========================= */

    fun getMaxOverfill(context: Context): Duration {
        return getPreferences(context).getInt(MAX_OVERFILL_MINUTES_KEY, DEFAULT_MAX_OVERFILL_MINUTES).minutes
    }

    fun setMaxOverfill(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                MAX_OVERFILL_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    fun getOverfillDecayPerHour(context: Context): Duration {
        return getPreferences(context).getInt(OVERFILL_DECAY_PER_HOUR_MINUTES_KEY, DEFAULT_OVERFILL_DECAY_PER_HOUR_MINUTES).minutes
    }

    fun setOverfillDecayPerHour(context: Context, duration: Duration) {
        getPreferences(context).edit {
            putInt(
                OVERFILL_DECAY_PER_HOUR_MINUTES_KEY,
                duration.inWholeMinutes.toInt()
            )
        }
    }

    fun getGoodAppFillRateMultiplier(context: Context): Float {
        return getPreferences(context).getFloat(GOOD_APP_FILL_RATE_MULTIPLIER_KEY, DEFAULT_GOOD_APP_FILL_RATE_MULTIPLIER.toFloat())
    }

    fun setGoodAppFillRateMultiplier(context: Context, multiplier: Float) {
        getPreferences(context).edit { putFloat(GOOD_APP_FILL_RATE_MULTIPLIER_KEY, multiplier) }
    }
}