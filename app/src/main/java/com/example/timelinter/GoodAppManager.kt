package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages the list of "good apps" that provide time rewards.
 * Similar to TimeWasterAppManager but for beneficial apps.
 * Caches display names to avoid repeated PackageManager lookups.
 */
object GoodAppManager {
    private const val PREF_NAME = "good_apps"
    private const val SELECTED_APPS_KEY = "selected_apps"
    private const val APP_NAMES_KEY = "app_display_names"
    private const val TAG = "GoodAppManager"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedApps(context: Context, packageNames: Set<String>) {
        val pm = context.packageManager
        
        // Cache display names for the selected packages
        val displayNames = mutableMapOf<String, String>()
        packageNames.forEach { pkg ->
            try {
                val appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                displayNames[pkg] = appName
            } catch (e: Exception) {
                Log.w(TAG, "Could not get display name for $pkg", e)
                // Store package name as fallback
                displayNames[pkg] = pkg
            }
        }
        
        getPreferences(context).edit().apply {
            putStringSet(SELECTED_APPS_KEY, packageNames)
            putString(APP_NAMES_KEY, JSONObject(displayNames as Map<*, *>).toString())
            apply()
        }
        Log.d(TAG, "Saved ${packageNames.size} good apps with display names")
    }

    fun getSelectedApps(context: Context): Set<String> {
        return getPreferences(context).getStringSet(SELECTED_APPS_KEY, emptySet()) ?: emptySet()
    }

    /**
     * Get display names of selected good apps.
     * Returns null if no good apps are configured.
     * Returns empty list if all configured apps have been uninstalled.
     */
    fun getSelectedAppDisplayNames(context: Context): List<String>? {
        val selectedPackages = getSelectedApps(context)
        if (selectedPackages.isEmpty()) {
            return null
        }
        
        val prefs = getPreferences(context)
        val namesJson = prefs.getString(APP_NAMES_KEY, null)
        
        if (namesJson != null) {
            try {
                val namesMap = JSONObject(namesJson)
                return selectedPackages.mapNotNull { pkg ->
                    namesMap.optString(pkg, null)
                }.sorted()
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing cached display names", e)
            }
        }
        
        // Fallback: look up names now and cache them
        val pm = context.packageManager
        val displayNames = mutableMapOf<String, String>()
        selectedPackages.forEach { pkg ->
            try {
                val appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                displayNames[pkg] = appName
            } catch (e: Exception) {
                Log.w(TAG, "App $pkg no longer installed, skipping", e)
            }
        }
        
        // Update cache
        prefs.edit().putString(APP_NAMES_KEY, JSONObject(displayNames as Map<*, *>).toString()).apply()
        
        return displayNames.values.toList().sorted()
    }

    fun isGoodApp(context: Context, packageName: String): Boolean {
        return getSelectedApps(context).contains(packageName)
    }
}




