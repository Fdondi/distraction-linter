package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TimeWasterAppManager {
    private const val PREF_NAME = "time_waster_apps"
    private const val SELECTED_APPS_KEY = "selected_apps"
    private const val TAG = "TimeWasterAppManager"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedApps(context: Context, packageNames: Set<String>) {
        getPreferences(context).edit().apply {
            putStringSet(SELECTED_APPS_KEY, packageNames)
            apply()
        }
        Log.d(TAG, "Saved ${packageNames.size} selected apps")
    }

    fun getSelectedApps(context: Context): Set<String> {
        return getPreferences(context).getStringSet(SELECTED_APPS_KEY, emptySet()) ?: emptySet()
    }

    fun isTimeWasterApp(context: Context, packageName: String): Boolean {
        return getSelectedApps(context).contains(packageName)
    }
} 