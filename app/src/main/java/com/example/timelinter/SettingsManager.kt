package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences

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
    private const val ACCUMULATED_NON_WASTEFUL_MS_KEY = "accumulated_non_wasteful_ms" // Internal accumulator for replenishment

    // Defaults
    private const val DEFAULT_MAX_THRESHOLD_MINUTES = 5
    private const val DEFAULT_REPLENISH_INTERVAL_MINUTES = 10
    private const val DEFAULT_REPLENISH_AMOUNT_MINUTES = 1

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getObserveTimerMinutes(context: Context): Int {
        return getPreferences(context).getInt(OBSERVE_TIMER_MINUTES_KEY, DEFAULT_OBSERVE_TIMER_MINUTES)
    }

    fun setObserveTimerMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(OBSERVE_TIMER_MINUTES_KEY, minutes).apply()
    }

    fun getResponseTimerMinutes(context: Context): Int {
        return getPreferences(context).getInt(RESPONSE_TIMER_MINUTES_KEY, DEFAULT_RESPONSE_TIMER_MINUTES)
    }

    fun setResponseTimerMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(RESPONSE_TIMER_MINUTES_KEY, minutes).apply()
    }

    /* =========================  Threshold Settings  ========================= */

    fun getMaxThresholdMinutes(context: Context): Int {
        return getPreferences(context).getInt(MAX_THRESHOLD_MINUTES_KEY, DEFAULT_MAX_THRESHOLD_MINUTES)
    }

    fun setMaxThresholdMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(MAX_THRESHOLD_MINUTES_KEY, minutes).apply()
    }

    fun getReplenishIntervalMinutes(context: Context): Int {
        return getPreferences(context).getInt(REPLENISH_INTERVAL_MINUTES_KEY, DEFAULT_REPLENISH_INTERVAL_MINUTES)
    }

    fun setReplenishIntervalMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(REPLENISH_INTERVAL_MINUTES_KEY, minutes).apply()
    }

    fun getReplenishAmountMinutes(context: Context): Int {
        return getPreferences(context).getInt(REPLENISH_AMOUNT_MINUTES_KEY, DEFAULT_REPLENISH_AMOUNT_MINUTES)
    }

    fun setReplenishAmountMinutes(context: Context, minutes: Int) {
        getPreferences(context).edit().putInt(REPLENISH_AMOUNT_MINUTES_KEY, minutes).apply()
    }

    /* =========================  Threshold Remaining (persistence)  ========================= */

    fun getThresholdRemainingMs(context: Context, defaultValue: Long): Long {
        return getPreferences(context).getLong(THRESHOLD_REMAINING_MS_KEY, defaultValue)
    }

    fun setThresholdRemainingMs(context: Context, value: Long) {
        getPreferences(context).edit().putLong(THRESHOLD_REMAINING_MS_KEY, value).apply()
    }

    /* ================= Accumulated Non-Wasteful Time (persistence) ================ */

    fun getAccumulatedNonWastefulMs(context: Context, defaultValue: Long): Long {
        return getPreferences(context).getLong(ACCUMULATED_NON_WASTEFUL_MS_KEY, defaultValue)
    }

    fun setAccumulatedNonWastefulMs(context: Context, value: Long) {
        getPreferences(context).edit().putLong(ACCUMULATED_NON_WASTEFUL_MS_KEY, value).apply()
    }
}