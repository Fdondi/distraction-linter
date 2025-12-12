package com.timelinter.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class CategoryParameters(
    val id: String,
    val label: String,
    val minutesChangePerMinute: Float?, // Null means use neutral timer behavior
    val freeMinutesPerPeriod: Int = 0,
    val freePeriodsPerDay: Int = 0,
    val allowOverfill: Boolean = false,
    val usesNeutralTimers: Boolean = false,
    val emoji: String = ""
)

data class ResolvedCategory(
    val id: String,
    val label: String,
    val minutesChangePerMinute: Float?,
    val freeMinutesPerPeriod: Int,
    val freePeriodsPerDay: Int,
    val allowOverfill: Boolean,
    val usesNeutralTimers: Boolean,
    val emoji: String = ""
)

object AppCategoryIds {
    const val DEFAULT = "default"
    const val BAD = "bad"
    const val SUSPECT = "suspect"
    const val GOOD = "good"
    const val CUSTOM = "custom"
}

class AppCategoryConfigManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_category_config_v2", Context.MODE_PRIVATE)

    private val assignmentsKey = "app_assignments"
    private val customParamsKey = "app_custom_params"
    private val categoriesKey = "categories"
    private val explanationsKey = "app_explanations"

    fun resetForTests() {
        prefs.edit().clear().commit()
    }

    fun getCategories(): List<CategoryParameters> {
        val stored = prefs.getString(categoriesKey, null)
        if (stored.isNullOrEmpty()) {
            val defaults = defaultCategories()
            persistCategories(defaults)
            return defaults
        }
        return try {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { idx ->
                array.optJSONObject(idx)?.toCategoryParameters()
            }
        } catch (_: Exception) {
            val defaults = defaultCategories()
            persistCategories(defaults)
            defaults
        }
    }

    fun addOrUpdateCategory(category: CategoryParameters) {
        if (isSpecialCategory(category.id)) return
        val updated =
            (getCategories().filterNot { it.id == category.id } + category).sortedBy { it.label }
        persistCategories(updated)
    }

    fun removeCategory(categoryId: String) {
        if (isSpecialCategory(categoryId)) return
        val remaining = getCategories().filterNot { it.id == categoryId }
        persistCategories(remaining)

        // Reassign apps that were using this category back to default
        val assignments = loadAssignments().toMutableMap()
        val filtered =
            assignments.filterValues { it != categoryId }.toMutableMap()
        if (assignments.size != filtered.size) {
            persistAssignments(filtered)
        }
    }

    fun assignAppToCategory(
        packageName: String,
        categoryId: String,
        customParams: CategoryParameters? = null
    ) {
        val assignments = loadAssignments().toMutableMap()
        assignments[packageName] = categoryId
        persistAssignments(assignments)

        if (categoryId == AppCategoryIds.CUSTOM && customParams != null) {
            val custom = loadCustomParams().toMutableMap()
            custom[packageName] = customParams
            persistCustomParams(custom)
        } else {
            val custom = loadCustomParams().toMutableMap()
            if (custom.remove(packageName) != null) {
                persistCustomParams(custom)
            }
        }
    }

    fun resolveCategory(packageName: String): ResolvedCategory {
        val assignments = loadAssignments()
        val categoryId = assignments[packageName] ?: AppCategoryIds.DEFAULT

        if (categoryId == AppCategoryIds.CUSTOM) {
            val customParams = loadCustomParams()[packageName]
            if (customParams != null) {
                return customParams.asResolved()
            }
        }

        val category =
            getCategories().firstOrNull { it.id == categoryId }
                ?: defaultCategory()
        return category.asResolved()
    }

    fun getAppsForCategory(categoryId: String): Set<String> {
        val assignments = loadAssignments()
        return assignments.filterValues { it == categoryId }.keys
    }

    fun getAppAssignments(): Map<String, String> = loadAssignments()

    fun getCustomParams(packageName: String): CategoryParameters? = loadCustomParams()[packageName]

    fun getDefaultResolvedCategory(): ResolvedCategory = defaultCategory().asResolved()

    fun getCustomResolvedCategory(): ResolvedCategory =
        CategoryParameters(
            id = AppCategoryIds.CUSTOM,
            label = "Custom",
            minutesChangePerMinute = null,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = true,
            emoji = "🛠"
        ).asResolved()

    fun getDisplayCategories(): List<ResolvedCategory> {
        val base = getCategories().map { it.asResolved() }
        return listOf(getDefaultResolvedCategory()) + base + listOf(getCustomResolvedCategory())
    }

    fun setExplanation(packageName: String, explanation: String) {
        val map = loadExplanations().toMutableMap()
        if (explanation.isBlank()) {
            map.remove(packageName)
        } else {
            map[packageName] = explanation
        }
        persistExplanations(map)
    }

    fun getExplanation(packageName: String): String {
        return loadExplanations()[packageName] ?: ""
    }

    fun getAllExplanations(): Map<String, String> = loadExplanations()

    fun migrateFromLegacyIfEmpty() {
        if (loadAssignments().isNotEmpty()) return
        val legacyWasteful = TimeWasterAppManager.getSelectedApps(context)
        val legacyGood = GoodAppManager.getSelectedApps(context)
        if (legacyWasteful.isEmpty() && legacyGood.isEmpty()) return

        val assignments = mutableMapOf<String, String>()
        legacyWasteful.forEach { pkg ->
            assignments[pkg] = AppCategoryIds.BAD
            val explanation = TimeWasterAppManager.getExplanation(context, pkg)
            if (explanation.isNotBlank()) {
                setExplanation(pkg, explanation)
            }
        }
        legacyGood.forEach { pkg ->
            assignments[pkg] = AppCategoryIds.GOOD
            val explanation = GoodAppManager.getExplanation(context, pkg)
            if (explanation.isNotBlank()) {
                setExplanation(pkg, explanation)
            }
        }
        persistAssignments(assignments)
    }

    private fun defaultCategories(): List<CategoryParameters> =
        listOf(
            CategoryParameters(
                id = AppCategoryIds.BAD,
                label = "Bad",
                minutesChangePerMinute = -1f,
                freeMinutesPerPeriod = 0,
                freePeriodsPerDay = 0,
                allowOverfill = false,
                usesNeutralTimers = false,
                emoji = "💀"
            ),
            CategoryParameters(
                id = AppCategoryIds.SUSPECT,
                label = "Suspect",
                minutesChangePerMinute = -0.2f,
                freeMinutesPerPeriod = 10,
                freePeriodsPerDay = 4,
                allowOverfill = false,
                usesNeutralTimers = false,
                emoji = "🤔"
            ),
            CategoryParameters(
                id = AppCategoryIds.GOOD,
                label = "Good",
                minutesChangePerMinute = 5f,
                freeMinutesPerPeriod = 0,
                freePeriodsPerDay = 0,
                allowOverfill = true,
                usesNeutralTimers = false,
                emoji = "🌸"
            )
        )

    private fun defaultCategory(): CategoryParameters =
        CategoryParameters(
            id = AppCategoryIds.DEFAULT,
            label = "Default",
            minutesChangePerMinute = null,
            freeMinutesPerPeriod = 0,
            freePeriodsPerDay = 0,
            allowOverfill = false,
            usesNeutralTimers = true,
            emoji = "⏸"
        )

    private fun CategoryParameters.asResolved(): ResolvedCategory =
        ResolvedCategory(
            id = id,
            label = label,
            minutesChangePerMinute = minutesChangePerMinute,
            freeMinutesPerPeriod = freeMinutesPerPeriod,
            freePeriodsPerDay = freePeriodsPerDay,
            allowOverfill = allowOverfill || minutesChangePerMinute?.let { it > 0 } == true,
            usesNeutralTimers = usesNeutralTimers || minutesChangePerMinute == null,
            emoji = emoji
        )

    private fun persistCategories(categories: List<CategoryParameters>) {
        val array = JSONArray()
        categories.forEach { category ->
            array.put(category.toJson())
        }
        prefs.edit().putString(categoriesKey, array.toString()).apply()
    }

    private fun loadAssignments(): Map<String, String> {
        val raw = prefs.getString(assignmentsKey, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> obj.optString(key, AppCategoryIds.DEFAULT) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistAssignments(assignments: Map<String, String>) {
        val obj = JSONObject()
        assignments.forEach { (pkg, cat) ->
            obj.put(pkg, cat)
        }
        prefs.edit().putString(assignmentsKey, obj.toString()).apply()
    }

    private fun loadCustomParams(): Map<String, CategoryParameters> {
        val raw = prefs.getString(customParamsKey, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().mapNotNull { pkg ->
                obj.optJSONObject(pkg)?.toCategoryParameters()?.let { params ->
                    pkg to params
                }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistCustomParams(params: Map<String, CategoryParameters>) {
        val obj = JSONObject()
        params.forEach { (pkg, param) ->
            obj.put(pkg, param.toJson())
        }
        prefs.edit().putString(customParamsKey, obj.toString()).apply()
    }

    private fun loadExplanations(): Map<String, String> {
        val raw = prefs.getString(explanationsKey, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> obj.optString(key, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistExplanations(explanations: Map<String, String>) {
        val obj = JSONObject()
        explanations.forEach { (pkg, explanation) ->
            obj.put(pkg, explanation)
        }
        prefs.edit().putString(explanationsKey, obj.toString()).apply()
    }

    private fun CategoryParameters.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("label", label)
            if (minutesChangePerMinute != null) {
                put("minutesChangePerMinute", minutesChangePerMinute.toDouble())
            }
            put("freeMinutesPerPeriod", freeMinutesPerPeriod)
            put("freePeriodsPerDay", freePeriodsPerDay)
            put("allowOverfill", allowOverfill)
            put("usesNeutralTimers", usesNeutralTimers)
            put("emoji", emoji)
        }

    private fun JSONObject.toCategoryParameters(): CategoryParameters? {
        val id = optString("id", "")
        val label = optString("label", "")
        if (id.isBlank() || label.isBlank()) return null
        val rate =
            if (has("minutesChangePerMinute")) optDouble("minutesChangePerMinute").toFloat()
            else null
        return CategoryParameters(
            id = id,
            label = label,
            minutesChangePerMinute = rate,
            freeMinutesPerPeriod = optInt("freeMinutesPerPeriod", 0),
            freePeriodsPerDay = optInt("freePeriodsPerDay", 0),
            allowOverfill = optBoolean("allowOverfill", rate?.let { it > 0 } == true),
            usesNeutralTimers = optBoolean("usesNeutralTimers", rate == null),
            emoji = optString("emoji", "")
        )
    }

    private fun isSpecialCategory(id: String): Boolean {
        return id == AppCategoryIds.DEFAULT || id == AppCategoryIds.CUSTOM
    }
}

