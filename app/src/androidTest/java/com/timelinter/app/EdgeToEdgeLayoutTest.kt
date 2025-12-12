package com.timelinter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Instrumented test to verify edge-to-edge layout works correctly.
 * Tests that the top bar and content are not hidden behind the system status bar.
 */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeLayoutTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val context = composeTestRule.activity
        // Ensure main flow is shown (skip first-boot tutorial and have at least one wasteful app)
        ApiKeyManager.setFirstBootTutorialShown(context)
        TimeWasterAppManager.saveSelectedApps(context, setOf("com.example.dummy"))
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun primePrefs() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ApiKeyManager.setFirstBootTutorialShown(context)
            TimeWasterAppManager.saveSelectedApps(context, setOf("com.example.dummy"))
        }
    }

    @Test
    fun testTopBarNotHiddenBehindStatusBar() {
        // Wait for the UI to be fully rendered
        composeTestRule.waitForIdle()

        // Find the top bar by looking for one of the icon buttons
        val topBarButton = composeTestRule.onNodeWithContentDescription("Wasteful Apps")
        
        // Verify the top bar exists
        topBarButton.assertExists()

        // Get the bounds of the top bar button
        val buttonBounds = topBarButton.fetchSemanticsNode().boundsInRoot

        // The top bar button should not be at the very top (y = 0)
        // It should be positioned below the status bar
        // On most devices, status bar is around 24-48dp
        assertTrue(
            "Top bar button should be positioned below status bar (y > 0), but was at y=${buttonBounds.top}",
            buttonBounds.top > 0f
        )

        // The button should be visible and not have a negative position
        assertTrue(
            "Top bar button should have positive y coordinate",
            buttonBounds.top >= 0f
        )
    }

    @Test
    fun testContentNotClippedByStatusBar() {
        composeTestRule.waitForIdle()

        // Find a content element that should be visible (the app name in the top bar)
        val appTitle = composeTestRule.onNodeWithText("TimeLinter")
        
        // Verify it exists and is displayed
        appTitle.assertExists()
        appTitle.assertIsDisplayed()

        // Get the bounds
        val titleBounds = appTitle.fetchSemanticsNode().boundsInRoot

        // Title should be positioned with some offset from the top
        assertTrue(
            "App title should not be at the very top edge (y > 0)",
            titleBounds.top > 0f
        )
    }

    @Test
    fun testWindowInsetsAreApplied() {
        composeTestRule.waitForIdle()

        // Verify that the system bar insets are available and applied to layout
        var statusBarInset = 0f
        composeTestRule.activity.runOnUiThread {
            val window = composeTestRule.activity.window
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            statusBarInset =
                insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top?.toFloat() ?: 0f
        }

        // System bar inset should be greater than zero in edge-to-edge mode
        assertTrue("Status bar inset should be reported", statusBarInset > 0f)

        // Top bar content should be laid out below the status bar inset
        val topBarButton = composeTestRule.onNodeWithContentDescription("Wasteful Apps")
        topBarButton.assertExists()
        val buttonBounds = topBarButton.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Top bar should respect status bar inset",
            buttonBounds.top >= statusBarInset
        )
    }

    @Test
    fun testTopBarHeightIsReasonable() {
        composeTestRule.waitForIdle()

        // Find multiple top bar elements
        val wastefulAppsButton = composeTestRule.onNodeWithContentDescription("Wasteful Apps")
        val goodAppsButton = composeTestRule.onNodeWithContentDescription("Good Apps")
        
        wastefulAppsButton.assertExists()
        goodAppsButton.assertExists()

        val button1Bounds = wastefulAppsButton.fetchSemanticsNode().boundsInRoot
        val button2Bounds = goodAppsButton.fetchSemanticsNode().boundsInRoot

        // Both buttons should be on roughly the same vertical level (in the same top bar)
        val verticalDifference = kotlin.math.abs(button1Bounds.top - button2Bounds.top)
        assertTrue(
            "Top bar buttons should be at similar heights (difference < 10dp), but difference was ${verticalDifference}",
            verticalDifference < 10f * composeTestRule.density.density
        )

        // Top bar should be in the top portion of the screen
        // Let's say within the first 150dp (accounting for status bar + app bar)
        assertTrue(
            "Top bar should be in the top portion of screen (< 150dp from top)",
            button1Bounds.top < 150f * composeTestRule.density.density
        )
    }

    @Test
    fun testScrollableContentDoesNotOverlapTopBar() {
        composeTestRule.waitForIdle()

        // Navigate to Timer Settings which has scrollable content
        composeTestRule.onNodeWithContentDescription("Timer Settings").performClick()
        composeTestRule.waitForIdle()

        // Find the back button in the timer settings screen
        val backButton = composeTestRule.onNodeWithContentDescription("Back")
        backButton.assertExists()
        backButton.assertIsDisplayed()

        // Get its position
        val backButtonBounds = backButton.fetchSemanticsNode().boundsInRoot

        // The back button should be positioned with proper insets
        assertTrue(
            "Back button in Timer Settings should be below status bar",
            backButtonBounds.top > 0f
        )

        // Find a scrollable content element
        val observeTimerCard = composeTestRule.onNodeWithText("Observe Timer", substring = true)
        observeTimerCard.assertExists()

        val contentBounds = observeTimerCard.fetchSemanticsNode().boundsInRoot

        // Content should be below the back button (not overlapping top bar)
        assertTrue(
            "Scrollable content should be below the top bar",
            contentBounds.top > backButtonBounds.bottom
        )
    }

    @Test
    fun testMultipleScreensHaveProperInsets() {
        composeTestRule.waitForIdle()

        // Test main screen
        var topButton = composeTestRule.onNodeWithContentDescription("Wasteful Apps")
        topButton.assertExists()
        var mainScreenTopY = topButton.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Main screen top bar should be below status bar", mainScreenTopY > 0f)

        // Navigate to Good Apps screen
        composeTestRule.onNodeWithContentDescription("Good Apps").performClick()
        composeTestRule.waitForIdle()
        
        val goodAppsBackButton = composeTestRule.onNodeWithContentDescription("Back")
        goodAppsBackButton.assertExists()
        val goodAppsTopY = goodAppsBackButton.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Good Apps screen top bar should be below status bar", goodAppsTopY > 0f)
        
        // Go back
        goodAppsBackButton.performClick()
        composeTestRule.waitForIdle()

        // Navigate to AI Log screen
        composeTestRule.onNodeWithContentDescription("AI Log").performClick()
        composeTestRule.waitForIdle()
        
        val logBackButton = composeTestRule.onNodeWithContentDescription("Back")
        logBackButton.assertExists()
        val logTopY = logBackButton.fetchSemanticsNode().boundsInRoot.top
        assertTrue("AI Log screen top bar should be below status bar", logTopY > 0f)

        // All screens should have similar top bar positioning
        val topYPositions = listOf(mainScreenTopY, goodAppsTopY, logTopY)
        val minY = topYPositions.minOrNull() ?: 0f
        val maxY = topYPositions.maxOrNull() ?: 0f
        val difference = maxY - minY
        
        assertTrue(
            "All screens should have consistent top bar positioning (difference < 5dp), but difference was $difference",
            difference < 5f * composeTestRule.density.density
        )
    }
}





