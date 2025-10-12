package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages the list of "good apps" that provide time rewards.
 * Similar to TimeWasterAppManager but for beneficial apps.
 */
object GoodAppManager {
    private const val PREF_NAME = "good_apps"
    private const val SELECTED_APPS_KEY = "selected_apps"
    private const val TAG = "GoodAppManager"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedApps(context: Context, packageNames: Set<String>) {
        getPreferences(context).edit().apply {
            putStringSet(SELECTED_APPS_KEY, packageNames)
            apply()
        }
        Log.d(TAG, "Saved ${packageNames.size} good apps")
    }

    fun getSelectedApps(context: Context): Set<String> {
        return getPreferences(context).getStringSet(SELECTED_APPS_KEY, emptySet()) ?: emptySet()
    }

    fun isGoodApp(context: Context, packageName: String): Boolean {
        return getSelectedApps(context).contains(packageName)
    }
}




