package com.example.timelinter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AIConfigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear any existing configuration
        val prefs = context.getSharedPreferences("ai_config", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testAIConfigScreenDisplays() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Verify screen title
        composeTestRule.onNodeWithText("AI Configuration").assertExists()
    }

    @Test
    fun testTaskListDisplayed() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Verify all tasks are displayed
        composeTestRule.onNodeWithText("First Message").assertExists()
        composeTestRule.onNodeWithText("Follow-up (No Response)").assertExists()
        composeTestRule.onNodeWithText("Response to User").assertExists()
    }

    @Test
    fun testModelSelectionForTask() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Click on first message task to expand model selection
        composeTestRule.onNodeWithText("First Message").performClick()

        // Wait for dropdown to appear
        composeTestRule.waitForIdle()

        // Verify available models are shown
        composeTestRule.onNodeWithText("Gemini 2.5 Pro")
            .assertExists()
    }

    @Test
    fun testChangeModelForTask() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Expand first message task dropdown
        composeTestRule.onNodeWithText("First Message").performClick()
        composeTestRule.waitForIdle()

        // Select a different model
        composeTestRule.onNodeWithText("Gemini 2.5 Flash").performClick()
        composeTestRule.waitForIdle()

        // Verify the model was changed
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assert(config.modelName == "gemini-2.5-flash")
    }

    @Test
    fun testResetToDefaultsButton() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // First, set a custom model
        AIConfigManager.setModelForTask(
            context,
            AITask.FIRST_MESSAGE,
            AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        )

        // Click reset button
        composeTestRule.onNodeWithText("Reset to Defaults").performClick()
        composeTestRule.waitForIdle()

        // Verify default model is restored
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assert(config.modelName == "gemini-2.5-pro")
    }

    @Test
    fun testExportConfiguration() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Set a custom configuration
        AIConfigManager.setModelForTask(
            context,
            AITask.FIRST_MESSAGE,
            AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        )

        // Click export button
        composeTestRule.onNodeWithText("Export Config").performClick()
        composeTestRule.waitForIdle()

        // Verify export dialog or message appears
        // (Implementation depends on how export is handled)
    }

    @Test
    fun testImportConfiguration() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Click import button
        composeTestRule.onNodeWithText("Import Config").performClick()
        composeTestRule.waitForIdle()

        // Verify import dialog appears
        // (Implementation depends on how import is handled)
    }

    @Test
    fun testModelDescriptionDisplayed() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Each task should show current model description
        composeTestRule.onNodeWithText("First Message").assertExists()
        
        // Model description should be visible under task
        composeTestRule.onNodeWithTag("model_description_FIRST_MESSAGE").assertExists()
    }

    @Test
    fun testAllTasksConfigurable() {
        composeTestRule.setContent {
            AIConfigScreen()
        }

        // Verify each AI task has configuration UI
        val tasks = listOf("First Message", "Follow-up (No Response)", "Response to User")
        
        tasks.forEach { taskName ->
            composeTestRule.onNodeWithText(taskName).assertExists()
            composeTestRule.onNodeWithText(taskName).performClick()
            composeTestRule.waitForIdle()
            
            // Should be able to see model options
            val taskEnumName = when(taskName) {
                "First Message" -> "FIRST_MESSAGE"
                "Follow-up (No Response)" -> "FOLLOWUP_NO_RESPONSE"
                "Response to User" -> "USER_RESPONSE"
                else -> taskName.uppercase()
            }
            composeTestRule.onNodeWithTag("model_selector_$taskEnumName")
                .assertExists()
        }
    }
}