package com.timelinter.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthPromptOnboardingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val context = composeRule.activity
        ApiKeyManager.clearKey(context)
        ApiKeyManager.clearGoogleIdToken(context)
        SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
        composeRule.runOnUiThread {
            composeRule.activity.refreshAuthStateForTests()
        }
    }

    @After
    fun tearDown() {
        val context = composeRule.activity
        ApiKeyManager.clearKey(context)
        ApiKeyManager.clearGoogleIdToken(context)
        SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
        composeRule.runOnUiThread {
            composeRule.activity.refreshAuthStateForTests()
        }
    }

    @Test
    fun showsGoogleLoginPromptWhenNoToken() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("googleSignInCard").assertExists()
        composeRule.onNodeWithTag("googleSignInButton").assertExists()
        composeRule.onAllNodesWithText("Gemini API Key Required").assertCountEquals(0)
        composeRule.onNodeWithText("Start Monitoring").assertIsNotEnabled()
    }

    @Test
    fun enablesMonitoringAfterTokenIsPresent() {
        val context = composeRule.activity
        ApiKeyManager.saveGoogleIdToken(context, "test-token")
        composeRule.runOnUiThread {
            composeRule.activity.refreshAuthStateForTests()
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("googleSignedInStatus").assertExists()
        composeRule.onNodeWithText("Start Monitoring").assertIsEnabled()
    }
}

