package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppCategoryConfigManagerTest {

    private lateinit var context: Context
    private lateinit var manager: AppCategoryConfigManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = AppCategoryConfigManager(context)
        manager.resetForTests()
        context.getSharedPreferences("app_category_good_apps", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_category_time_waster_apps", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaults_includeBuiltInCategories() {
        val categories = manager.getCategories()
        val ids = categories.map { it.id }.toSet()

        assertTrue(ids.contains(AppCategoryIds.BAD))
        assertTrue(ids.contains(AppCategoryIds.SUSPECT))
        assertTrue(ids.contains(AppCategoryIds.GOOD))

        val bad = categories.first { it.id == AppCategoryIds.BAD }
        assertEquals(-1f, bad.minutesChangePerMinute)
        assertEquals(0, bad.freeMinutesPerPeriod)
        assertEquals(0, bad.freePeriodsPerDay)
        assertEquals("💀", bad.emoji)

        val suspect = categories.first { it.id == AppCategoryIds.SUSPECT }
        assertEquals(-0.2f, suspect.minutesChangePerMinute)
        assertEquals(10, suspect.freeMinutesPerPeriod)
        assertEquals(4, suspect.freePeriodsPerDay)
        assertEquals("🤔", suspect.emoji)

        val good = categories.first { it.id == AppCategoryIds.GOOD }
        assertEquals(5f, good.minutesChangePerMinute)
        assertTrue(good.allowOverfill)
        assertEquals("🌸", good.emoji)
    }

    @Test
    fun assigns_and_resolves_app_categories() {
        val pkgBad = "com.example.bad"
        val pkgSuspect = "com.example.suspect"
        val pkgDefault = "com.example.defaulted"

        manager.assignAppToCategory(pkgBad, AppCategoryIds.BAD)
        manager.assignAppToCategory(pkgSuspect, AppCategoryIds.SUSPECT)

        val badResolved = manager.resolveCategory(pkgBad)
        assertEquals(AppCategoryIds.BAD, badResolved.id)
        assertEquals(-1f, badResolved.minutesChangePerMinute)

        val suspectResolved = manager.resolveCategory(pkgSuspect)
        assertEquals(AppCategoryIds.SUSPECT, suspectResolved.id)
        assertEquals(-0.2f, suspectResolved.minutesChangePerMinute)
        assertEquals(4, suspectResolved.freePeriodsPerDay)
        assertEquals(10, suspectResolved.freeMinutesPerPeriod)

        val defaultResolved = manager.resolveCategory(pkgDefault)
        assertEquals(AppCategoryIds.DEFAULT, defaultResolved.id)
        assertEquals(null, defaultResolved.minutesChangePerMinute)
    }

    @Test
    fun assigns_custom_parameters_per_app() {
        val pkgCustom = "com.example.custom"
        val customParams = CategoryParameters(
            id = AppCategoryIds.CUSTOM,
            label = "Custom app",
            minutesChangePerMinute = 3.5f,
            freeMinutesPerPeriod = 7,
            freePeriodsPerDay = 2,
            allowOverfill = true,
            usesNeutralTimers = false
        )

        manager.assignAppToCategory(pkgCustom, AppCategoryIds.CUSTOM, customParams)

        val resolved = manager.resolveCategory(pkgCustom)
        assertEquals(AppCategoryIds.CUSTOM, resolved.id)
        assertEquals(3.5f, resolved.minutesChangePerMinute)
        assertEquals(7, resolved.freeMinutesPerPeriod)
        assertEquals(2, resolved.freePeriodsPerDay)
        assertTrue(resolved.allowOverfill)
    }

    @Test
    fun removing_category_reassigns_apps_to_default() {
        val pkg = "com.example.suspect"
        manager.assignAppToCategory(pkg, AppCategoryIds.SUSPECT)

        assertEquals(AppCategoryIds.SUSPECT, manager.resolveCategory(pkg).id)

        manager.removeCategory(AppCategoryIds.SUSPECT)

        val resolved = manager.resolveCategory(pkg)
        assertEquals(AppCategoryIds.DEFAULT, resolved.id)
        assertEquals(null, resolved.minutesChangePerMinute)

        val remainingCategories = manager.getCategories().map { it.id }.toSet()
        assertTrue(remainingCategories.contains(AppCategoryIds.BAD))
        assertTrue(remainingCategories.contains(AppCategoryIds.GOOD))
        assertTrue(!remainingCategories.contains(AppCategoryIds.SUSPECT))
    }

    @Test
    fun migrates_legacy_selections_when_empty() {
        val badPkg = "com.example.bad"
        val goodPkg = "com.example.good"
        TimeWasterAppManager.saveSelectedApps(context, setOf(badPkg))
        GoodAppManager.saveSelectedApps(context, setOf(goodPkg))

        manager.migrateFromLegacyIfEmpty()

        assertEquals(AppCategoryIds.BAD, manager.resolveCategory(badPkg).id)
        assertEquals(AppCategoryIds.GOOD, manager.resolveCategory(goodPkg).id)
    }

    @Test
    fun allows_add_update_with_emoji_and_persists() {
        val custom = CategoryParameters(
            id = "focus",
            label = "Focus",
            minutesChangePerMinute = 3.5f,
            freeMinutesPerPeriod = 5,
            freePeriodsPerDay = 1,
            allowOverfill = true,
            usesNeutralTimers = false,
            emoji = "🎯"
        )

        manager.addOrUpdateCategory(custom)
        val stored = manager.getCategories().first { it.id == "focus" }
        assertEquals("🎯", stored.emoji)

        val resolved = manager.resolveCategory("com.example.app").copy(id = "focus", label = "Focus")
        val display = manager.getDisplayCategories().first { it.id == "focus" }
        assertEquals("🎯", display.emoji)
        assertTrue(resolved.allowOverfill)
    }
}

