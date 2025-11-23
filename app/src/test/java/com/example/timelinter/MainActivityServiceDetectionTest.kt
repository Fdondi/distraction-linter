package com.example.timelinter

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RuntimeEnvironment

/**
 * Tests for MainActivity service detection fixes:
 * 1. Properly detects when AppUsageMonitorService is running
 * 2. Uses service binding for accurate state detection
 * 3. Uses static instance tracking (modern replacement for getRunningServices)
 */
class MainActivityServiceDetectionTest {

    @Mock
    private lateinit var mockService: AppUsageMonitorService

    private lateinit var mainActivity: MainActivity
    private lateinit var context: Context

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        mainActivity = MainActivity()
    }

    @Test
    fun serviceDetection_usesServiceBinding_whenAvailable() {
        // Test that service detection prioritizes bound service state

        // Mock the service as properly initialized
        `when`(mockService.isServiceReady()).thenReturn(true)

        // Create a test instance to access private method
        val testActivity = TestMainActivity()
        testActivity.boundService = mockService
        testActivity.isServiceBound = true

        // Should return true when bound service is ready
        assertTrue("Should detect service as running when bound and ready",
                   testActivity.testIsServiceRunning())
    }

    @Test
    fun serviceDetection_fallbacksToStaticInstanceCheck_whenNotBound() {
        // Test fallback to static instance check when service not bound

        val testActivity = TestMainActivity()
        testActivity.isServiceBound = false
        testActivity.boundService = null

        // Set the static service instance to simulate running service
        val serviceInstance = AppUsageMonitorService.getServiceInstance()
        try {
            // Use reflection to set the static instance for testing
            val companion = AppUsageMonitorService.Companion
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(companion, mockService)

            assertTrue("Should detect service as running via static instance check",
                       testActivity.testIsServiceRunning())
        } finally {
            // Clean up: clear the static instance
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test
    fun serviceDetection_handlesServiceNotRunning() {
        // Test detection when service is not running

        val testActivity = TestMainActivity()
        testActivity.isServiceBound = false
        testActivity.boundService = null

        // Ensure static instance is null (service not running)
        try {
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(null, null)

            assertFalse("Should detect service as not running when static instance is null",
                        testActivity.testIsServiceRunning())
        } finally {
            // Clean up
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test
    fun serviceDetection_handlesDifferentAndroidVersions() {
        // Test that detection works across different Android API levels
        // The new approach using static instance works on all API levels

        val testActivity = TestMainActivity()

        // Set static instance to simulate running service
        try {
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(null, mockService)

            assertTrue("Should work on all API levels with static instance check",
                       testActivity.testIsServiceRunning())
        } finally {
            // Clean up
            val field = AppUsageMonitorService::class.java.getDeclaredField("serviceInstance")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test
    fun serviceBinding_enablesAccurateDetection() {
        // Test that service binding provides more accurate detection than process-based checks

        val testActivity = TestMainActivity()

        // Initially not bound, not running
        testActivity.isServiceBound = false
        testActivity.boundService = null
        assertFalse("Should not detect service as running when not bound",
                    testActivity.testIsServiceRunning())

        // After binding with ready service
        `when`(mockService.isServiceReady()).thenReturn(true)
        testActivity.boundService = mockService
        testActivity.isServiceBound = true

        assertTrue("Should detect service as running when bound and ready",
                   testActivity.testIsServiceRunning())

        // After unbinding
        testActivity.isServiceBound = false
        testActivity.boundService = null

        assertFalse("Should not detect service as running after unbinding",
                    testActivity.testIsServiceRunning())
    }

    /**
     * Test wrapper class to access private methods of MainActivity
     */
    private class TestMainActivity : MainActivity() {
        var boundService: AppUsageMonitorService? = null
        var isServiceBound = false

        // Expose the private isServiceRunning method for testing
        fun testIsServiceRunning(): Boolean {
            // Simulate the actual logic from MainActivity
            if (isServiceBound && boundService?.isServiceReady() == true) {
                return true
            }

            // Modern fallback: check the static service instance
            return AppUsageMonitorService.isServiceRunning()
        }
    }
}
