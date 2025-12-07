package com.timelinter.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test that GoodAppManager properly caches display names
 */
@RunWith(AndroidJUnit4::class)
class GoodAppManagerCacheTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any existing good apps
        GoodAppManager.saveSelectedApps(context, emptySet())
    }

    @After
    fun teardown() {
        GoodAppManager.saveSelectedApps(context, emptySet())
    }

    @Test
    fun testNoAppsConfigured_ReturnsNull() {
        // When no apps are configured
        val result = GoodAppManager.getSelectedAppDisplayNames(context)
        
        // Should return null, not empty list
        assertNull("Should return null when no apps configured", result)
    }

    @Test
    fun testAppsConfigured_ReturnsCachedNames() {
        // Save some apps (these package names exist in test environment)
        val packages = setOf(context.packageName) // Use our own package as a test
        GoodAppManager.saveSelectedApps(context, packages)
        
        // Get display names
        val names = GoodAppManager.getSelectedAppDisplayNames(context)
        
        // Should return non-null list
        assertNotNull("Should return list when apps configured", names)
        assertTrue("Should have at least one name", names!!.isNotEmpty())
    }

    @Test
    fun testCachedNamesAreSorted() {
        // Save multiple apps
        val packages = setOf(
            context.packageName,
            "com.android.settings"
        )
        GoodAppManager.saveSelectedApps(context, packages)
        
        // Get display names
        val names = GoodAppManager.getSelectedAppDisplayNames(context)
        
        // Should be sorted
        assertNotNull(names)
        if (names!!.size > 1) {
            val sorted = names.sorted()
            assertEquals("Names should be sorted", sorted, names)
        }
    }

    @Test
    fun testUninstalledApps_ExcludedFromList() {
        // Save some apps including a fake one that doesn't exist
        val packages = setOf(
            context.packageName,
            "com.fake.nonexistent.app"
        )
        GoodAppManager.saveSelectedApps(context, packages)
        
        // Get display names - should only include the valid one
        val names = GoodAppManager.getSelectedAppDisplayNames(context)
        
        assertNotNull(names)
        // The fake app should be excluded
        assertEquals("Should only include valid apps", 1, names!!.size)
    }

    @Test
    fun testIsGoodApp_ChecksPackageName() {
        val testPackage = context.packageName
        GoodAppManager.saveSelectedApps(context, setOf(testPackage))
        
        assertTrue("Should identify good app", 
            GoodAppManager.isGoodApp(context, testPackage))
        assertFalse("Should not identify non-good app",
            GoodAppManager.isGoodApp(context, "com.fake.app"))
    }

    @Test
    fun testClearApps_ReturnsNull() {
        // First configure some apps
        GoodAppManager.saveSelectedApps(context, setOf(context.packageName))
        assertNotNull(GoodAppManager.getSelectedAppDisplayNames(context))
        
        // Then clear them
        GoodAppManager.saveSelectedApps(context, emptySet())
        
        // Should return null
        assertNull(GoodAppManager.getSelectedAppDisplayNames(context))
    }
}

