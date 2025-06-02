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
} 