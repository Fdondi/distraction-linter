package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import kotlin.time.Duration.Companion.minutes

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

    /**
     * Free allowance per app: duration per session and daily uses.
     */
    fun setFreeAllowance(context: Context, packageName: String, minutes: kotlin.time.Duration, dailyUses: Int) {
        val prefs = getPreferences(context)
        val minutesJson = prefs.getString("app_free_minutes", null)
        val usesJson = prefs.getString("app_free_uses", null)

        val minutesObj = try { if (minutesJson != null) JSONObject(minutesJson) else JSONObject() } catch (e: Exception) { JSONObject() }
        val usesObj = try { if (usesJson != null) JSONObject(usesJson) else JSONObject() } catch (e: Exception) { JSONObject() }

        minutesObj.put(packageName, minutes.inWholeMinutes)
        usesObj.put(packageName, dailyUses)

        prefs.edit()
            .putString("app_free_minutes", minutesObj.toString())
            .putString("app_free_uses", usesObj.toString())
            .apply()
    }

    fun getFreeAllowance(context: Context, packageName: String, defaultMinutes: kotlin.time.Duration, defaultUses: Int): Pair<kotlin.time.Duration, Int> {
        val prefs = getPreferences(context)
        val minutesJson = prefs.getString("app_free_minutes", null)
        val usesJson = prefs.getString("app_free_uses", null)

        val minutes = try {
            if (minutesJson != null) {
                val raw = JSONObject(minutesJson).optInt(packageName, defaultMinutes.inWholeMinutes.toInt())
                raw.coerceAtLeast(0).toLong().minutes
            } else defaultMinutes
        } catch (e: Exception) { defaultMinutes }

        val uses = try {
            if (usesJson != null) JSONObject(usesJson).optInt(packageName, defaultUses) else defaultUses
        } catch (e: Exception) { defaultUses }

        return Pair(minutes, uses)
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

    fun setFreeAllowance(context: Context, packageName: String, minutes: kotlin.time.Duration, dailyUses: Int) =
        manager.setFreeAllowance(context, packageName, minutes, dailyUses)

    fun getFreeAllowance(context: Context, packageName: String, defaultMinutes: kotlin.time.Duration, defaultUses: Int) =
        manager.getFreeAllowance(context, packageName, defaultMinutes, defaultUses)
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

    fun setFreeAllowance(context: Context, packageName: String, minutes: kotlin.time.Duration, dailyUses: Int) =
        manager.setFreeAllowance(context, packageName, minutes, dailyUses)

    fun getFreeAllowance(context: Context, packageName: String, defaultMinutes: kotlin.time.Duration, defaultUses: Int) =
        manager.getFreeAllowance(context, packageName, defaultMinutes, defaultUses)
    
}

