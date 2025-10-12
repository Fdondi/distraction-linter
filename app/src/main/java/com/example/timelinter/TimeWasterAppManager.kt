package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TimeWasterAppManager {
    private const val PREF_NAME = "time_waster_apps"
    private const val SELECTED_APPS_KEY = "selected_apps"
    private const val SELECTED_SITES_KEY = "selected_sites"
    private const val APP_EXPLANATIONS_KEY = "app_explanations"
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

    fun saveSelectedSites(context: Context, hostnames: Set<String>) {
        getPreferences(context).edit().apply {
            putStringSet(SELECTED_SITES_KEY, hostnames)
            apply()
        }
        Log.d(TAG, "Saved ${hostnames.size} selected sites")
    }

    fun getSelectedSites(context: Context): Set<String> {
        return getPreferences(context).getStringSet(SELECTED_SITES_KEY, emptySet()) ?: emptySet()
    }

    fun isTimeWasterSite(context: Context, hostname: String): Boolean {
        return getSelectedSites(context).contains(hostname)
    }

    /**
     * Save explanation for why an app is bad/wasteful
     */
    fun saveExplanation(context: Context, packageName: String, explanation: String) {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString(APP_EXPLANATIONS_KEY, null)
        
        val explanations = try {
            if (explanationsJson != null) org.json.JSONObject(explanationsJson) else org.json.JSONObject()
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        
        if (explanation.isNotEmpty()) {
            explanations.put(packageName, explanation)
        } else {
            explanations.remove(packageName)
        }
        
        prefs.edit().putString(APP_EXPLANATIONS_KEY, explanations.toString()).apply()
        Log.d(TAG, "Saved explanation for $packageName")
    }

    /**
     * Get explanation for why an app is bad/wasteful
     */
    fun getExplanation(context: Context, packageName: String): String {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString(APP_EXPLANATIONS_KEY, null) ?: return ""
        
        return try {
            val explanations = org.json.JSONObject(explanationsJson)
            explanations.optString(packageName, "")
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing explanations", e)
            ""
        }
    }

    /**
     * Get all explanations as a map of package name to explanation
     * (does not resolve to app names since we don't cache those)
     */
    fun getAllExplanations(context: Context): Map<String, String> {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString(APP_EXPLANATIONS_KEY, null) ?: return emptyMap()
        
        return try {
            val explanations = org.json.JSONObject(explanationsJson)
            val result = mutableMapOf<String, String>()
            
            explanations.keys().forEach { pkg ->
                val explanation = explanations.getString(pkg)
                if (explanation.isNotEmpty()) {
                    result[pkg] = explanation
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing all explanations", e)
            emptyMap()
        }
    }
} 