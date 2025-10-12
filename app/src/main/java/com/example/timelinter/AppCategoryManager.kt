package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Generic manager for app categories (good apps, bad apps, etc.)
 * Handles app selection and explanations for any category
 */
class AppCategoryManager(
    private val category: String,
    private val tag: String = "AppCategoryManager"
) {
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences("app_category_$category", Context.MODE_PRIVATE)
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
                Log.w(tag, "Could not get display name for $pkg", e)
                displayNames[pkg] = pkg
            }
        }
        
        getPreferences(context).edit().apply {
            putStringSet("selected_apps", packageNames)
            putString("app_display_names", JSONObject(displayNames as Map<*, *>).toString())
            apply()
        }
        Log.d(tag, "Saved ${packageNames.size} apps for category $category")
    }

    fun getSelectedApps(context: Context): Set<String> {
        return getPreferences(context).getStringSet("selected_apps", emptySet()) ?: emptySet()
    }

    /**
     * Get display names of selected apps.
     * Returns null if no apps are configured.
     * Returns empty list if all configured apps have been uninstalled.
     */
    fun getSelectedAppDisplayNames(context: Context): List<String>? {
        val selectedPackages = getSelectedApps(context)
        if (selectedPackages.isEmpty()) {
            return null
        }
        
        val prefs = getPreferences(context)
        val namesJson = prefs.getString("app_display_names", null)
        
        if (namesJson != null) {
            try {
                val namesMap = JSONObject(namesJson)
                return selectedPackages.mapNotNull { pkg ->
                    val name = namesMap.optString(pkg, "")
                    if (name.isNotEmpty()) name else null
                }.sorted()
            } catch (e: Exception) {
                Log.w(tag, "Error parsing cached display names", e)
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
                Log.w(tag, "App $pkg no longer installed, skipping", e)
            }
        }
        
        // Update cache
        prefs.edit().putString("app_display_names", JSONObject(displayNames as Map<*, *>).toString()).apply()
        
        return displayNames.values.toList().sorted()
    }

    fun isInCategory(context: Context, packageName: String): Boolean {
        return getSelectedApps(context).contains(packageName)
    }

    /**
     * Save explanation for why an app is in this category
     */
    fun saveExplanation(context: Context, packageName: String, explanation: String) {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString("app_explanations", null)
        
        val explanations = try {
            if (explanationsJson != null) JSONObject(explanationsJson) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        
        if (explanation.isNotEmpty()) {
            explanations.put(packageName, explanation)
        } else {
            explanations.remove(packageName)
        }
        
        prefs.edit().putString("app_explanations", explanations.toString()).apply()
        Log.d(tag, "Saved explanation for $packageName in category $category")
    }

    /**
     * Get explanation for why an app is in this category
     */
    fun getExplanation(context: Context, packageName: String): String {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString("app_explanations", null) ?: return ""
        
        return try {
            val explanations = JSONObject(explanationsJson)
            explanations.optString(packageName, "")
        } catch (e: Exception) {
            Log.w(tag, "Error parsing explanations", e)
            ""
        }
    }

    /**
     * Get all explanations as a map of app name to explanation
     */
    fun getAllExplanations(context: Context): Map<String, String> {
        val prefs = getPreferences(context)
        val explanationsJson = prefs.getString("app_explanations", null) ?: return emptyMap()
        val namesJson = prefs.getString("app_display_names", null)
        
        return try {
            val explanations = JSONObject(explanationsJson)
            val names = if (namesJson != null) JSONObject(namesJson) else JSONObject()
            val result = mutableMapOf<String, String>()
            
            explanations.keys().forEach { pkg ->
                val explanation = explanations.getString(pkg)
                if (explanation.isNotEmpty()) {
                    val appName = names.optString(pkg, pkg)
                    result[appName] = explanation
                }
            }
            result
        } catch (e: Exception) {
            Log.w(tag, "Error parsing all explanations", e)
            emptyMap()
        }
    }
}

// Singleton instances for the two main categories
object GoodAppManager {
    private val manager = AppCategoryManager("good_apps", "GoodAppManager")
    
    fun saveSelectedApps(context: Context, packageNames: Set<String>) =
        manager.saveSelectedApps(context, packageNames)
    
    fun getSelectedApps(context: Context) = manager.getSelectedApps(context)
    
    fun getSelectedAppDisplayNames(context: Context) = manager.getSelectedAppDisplayNames(context)
    
    fun isGoodApp(context: Context, packageName: String) = manager.isInCategory(context, packageName)
    
    fun saveExplanation(context: Context, packageName: String, explanation: String) =
        manager.saveExplanation(context, packageName, explanation)
    
    fun getExplanation(context: Context, packageName: String) = manager.getExplanation(context, packageName)
    
    fun getAllExplanations(context: Context) = manager.getAllExplanations(context)
}

object TimeWasterAppManager {
    private val manager = AppCategoryManager("time_waster_apps", "TimeWasterAppManager")
    
    fun saveSelectedApps(context: Context, packageNames: Set<String>) =
        manager.saveSelectedApps(context, packageNames)
    
    fun getSelectedApps(context: Context) = manager.getSelectedApps(context)
    
    fun isTimeWasterApp(context: Context, packageName: String) = manager.isInCategory(context, packageName)
    
    fun saveExplanation(context: Context, packageName: String, explanation: String) =
        manager.saveExplanation(context, packageName, explanation)
    
    fun getExplanation(context: Context, packageName: String) = manager.getExplanation(context, packageName)
    
    fun getAllExplanations(context: Context) = manager.getAllExplanations(context)
    
    // Sites functionality (specific to time wasters)
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences("app_category_time_waster_apps", Context.MODE_PRIVATE)
    }
    
    fun saveSelectedSites(context: Context, hostnames: Set<String>) {
        getPreferences(context).edit().apply {
            putStringSet("selected_sites", hostnames)
            apply()
        }
        Log.d("TimeWasterAppManager", "Saved ${hostnames.size} selected sites")
    }

    fun getSelectedSites(context: Context): Set<String> {
        return getPreferences(context).getStringSet("selected_sites", emptySet()) ?: emptySet()
    }

    fun isTimeWasterSite(context: Context, hostname: String): Boolean {
        return getSelectedSites(context).contains(hostname)
    }
}

