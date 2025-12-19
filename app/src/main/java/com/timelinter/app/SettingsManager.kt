@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toDuration

object SettingsManager {
    private const val PREF_NAME = "timelinter_settings"
    private const val OBSERVE_TIMER_MINUTES_KEY = "observe_timer_minutes"
    private const val RESPONSE_TIMER_MINUTES_KEY = "response_timer_minutes"
    private const val WAKEUP_INTERVAL_SECONDS_KEY = "wakeup_interval_seconds"
    private const val NO_APP_GAP_MINUTES_KEY = "no_app_gap_minutes"
    private const val FREE_BUCKET_RESUME_MINUTES_KEY = "free_bucket_resume_minutes"
    
    // Default values
    private const val DEFAULT_OBSERVE_TIMER_MINUTES = 5
    private const val DEFAULT_RESPONSE_TIMER_MINUTES = 1
    private const val DEFAULT_WAKEUP_INTERVAL_SECONDS = 30L
    private const val DEFAULT_NO_APP_GAP_MINUTES = 2
    private const val DEFAULT_FREE_BUCKET_RESUME_MINUTES = 10

    // Threshold bucket settings
    private const val MAX_THRESHOLD_MINUTES_KEY = "max_threshold_minutes"
    private const val THRESHOLD_REMAINING_MS_KEY = "threshold_remaining_ms"           // Internal token bucket storage (ms)
    private const val THRESHOLD_LAST_UPDATE_MS_KEY = "threshold_last_update_ms"       // When the bucket was last updated (epoch ms)
    private const val REPLENISH_RATE_FRACTION_KEY = "replenish_rate_fraction"

    // Defaults
    private const val DEFAULT_MAX_THRESHOLD_MINUTES = 5
    private const val DEFAULT_REPLENISH_RATE_FRACTION = 0.1f

    // Good Apps settings keys
    private const val MAX_OVERFILL_MINUTES_KEY = "max_overfill_minutes"
    private const val OVERFILL_DECAY_PER_HOUR_MINUTES_KEY = "overfill_decay_per_hour_minutes"
    private const val GOOD_APP_FILL_RATE_MULTIPLIER_KEY = "good_app_fill_rate_multiplier"
    private const val NEUTRAL_APP_FILL_RATE_MULTIPLIER_KEY = "neutral_app_fill_rate_multiplier"
    private const val LOG_RETENTION_DAYS_KEY = "log_retention_days"

    // Good Apps default values
    private const val DEFAULT_MAX_OVERFILL_MINUTES = 30
    private const val DEFAULT_OVERFILL_DECAY_PER_HOUR_MINUTES = 10
    private const val DEFAULT_GOOD_APP_FILL_RATE_MULTIPLIER = 2
    private const val DEFAULT_NEUTRAL_APP_FILL_RATE_MULTIPLIER = 1
    private const val DEFAULT_LOG_RETENTION_DAYS = 14

    // AI Mode
    private const val AI_MODE_KEY = "ai_mode"
    const val AI_MODE_DIRECT = "direct" // kept for internal/dev only
    const val AI_MODE_BACKEND = "backend"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getAIMode(context: Context): String {
        val prefs = getPreferences(context)
        val stored = prefs.getString(AI_MODE_KEY, AI_MODE_BACKEND)
        // Force backend as the only supported mode; migrate any old "direct" value.
        return if (stored == AI_MODE_BACKEND) {
            AI_MODE_BACKEND
        } else {
            prefs.edit { putString(AI_MODE_KEY, AI_MODE_BACKEND) }
            AI_MODE_BACKEND
        }
    }

    fun setAIMode(context: Context, mode: String) {
        // Only backend is supported in production; ignore other values.
        val target = if (mode == AI_MODE_BACKEND) AI_MODE_BACKEND else AI_MODE_BACKEND
        getPreferences(context).edit { putString(AI_MODE_KEY, target) }
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

    fun getWakeupInterval(context: Context): Duration {
        val seconds = getPreferences(context).getLong(WAKEUP_INTERVAL_SECONDS_KEY, DEFAULT_WAKEUP_INTERVAL_SECONDS)
        return seconds.seconds
    }

    fun setWakeupInterval(context: Context, duration: Duration) {
        val clamped = duration.coerceIn(10.seconds, 10.minutes)
        getPreferences(context).edit {
            putLong(
                WAKEUP_INTERVAL_SECONDS_KEY,
                clamped.inWholeSeconds
            )
        }
    }

    fun getNoAppGapDuration(context: Context): Duration {
        val minutes = getPreferences(context).getInt(NO_APP_GAP_MINUTES_KEY, DEFAULT_NO_APP_GAP_MINUTES)
        return minutes.coerceAtLeast(0).minutes
    }

    fun setNoAppGapDuration(context: Context, duration: Duration) {
        val clamped = duration.inWholeMinutes.toInt().coerceAtLeast(0)
        getPreferences(context).edit { putInt(NO_APP_GAP_MINUTES_KEY, clamped) }
    }

    fun getFreeBucketResumeDuration(context: Context): Duration {
        val minutes = getPreferences(context).getInt(FREE_BUCKET_RESUME_MINUTES_KEY, DEFAULT_FREE_BUCKET_RESUME_MINUTES)
        return minutes.coerceAtLeast(0).minutes
    }

    fun setFreeBucketResumeDuration(context: Context, duration: Duration) {
        val clamped = duration.inWholeMinutes.toInt().coerceAtLeast(0)
        getPreferences(context).edit { putInt(FREE_BUCKET_RESUME_MINUTES_KEY, clamped) }
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

    /**
     * Replenish rate expressed as a dimensionless fraction of an hour.
     * Example: 6 minutes/hour = 0.1.
     */
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

    fun getReplenishRateFraction(context: Context): Float {
        return getPreferences(context)
            .getFloat(REPLENISH_RATE_FRACTION_KEY, DEFAULT_REPLENISH_RATE_FRACTION)
            .coerceAtLeast(0f)
    }

    fun setReplenishRateFraction(context: Context, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 10f)
        getPreferences(context).edit { putFloat(REPLENISH_RATE_FRACTION_KEY, clamped) }
    }

    // Replenish interval/amount are now represented via the fraction; legacy APIs removed.

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

    fun getThresholdLastUpdated(context: Context, defaultValue: Instant): Instant {
        val stored = getPreferences(context).getLong(THRESHOLD_LAST_UPDATE_MS_KEY, defaultValue.toEpochMilliseconds())
        return Instant.fromEpochMilliseconds(stored)
    }

    fun setThresholdLastUpdated(context: Context, instant: Instant) {
        getPreferences(context).edit {
            putLong(
                THRESHOLD_LAST_UPDATE_MS_KEY,
                instant.toEpochMilliseconds()
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

    fun getNeutralAppFillRateMultiplier(context: Context): Float {
        return getPreferences(context).getFloat(NEUTRAL_APP_FILL_RATE_MULTIPLIER_KEY, DEFAULT_NEUTRAL_APP_FILL_RATE_MULTIPLIER.toFloat())
    }

    fun setNeutralAppFillRateMultiplier(context: Context, multiplier: Float) {
        getPreferences(context).edit { putFloat(NEUTRAL_APP_FILL_RATE_MULTIPLIER_KEY, multiplier) }
    }

    fun getLogRetentionDays(context: Context): Int? {
        val raw = getPreferences(context).getInt(LOG_RETENTION_DAYS_KEY, DEFAULT_LOG_RETENTION_DAYS)
        return if (raw <= 0) null else raw
    }

    fun setLogRetentionDays(context: Context, days: Int?) {
        val value = days ?: -1
        getPreferences(context).edit { putInt(LOG_RETENTION_DAYS_KEY, value) }
    }
}
