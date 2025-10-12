package com.example.timelinter

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AIConfigManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("ai_config_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun testDefaultModelConfiguration() {
        // Should return default model when no configuration exists
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertNotNull(config)
        assertEquals("gemini-2.5-pro", config.modelName)
    }

    @Test
    fun testSetAndGetModelForTask() {
        // Set a specific model for a task
        val testModel = AIModelConfig(
            modelName = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = AIProvider.GOOGLE_AI
        )
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, testModel)
        
        // Retrieve and verify
        val retrieved = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals(testModel.modelName, retrieved.modelName)
        assertEquals(testModel.displayName, retrieved.displayName)
        assertEquals(testModel.provider, retrieved.provider)
    }

    @Test
    fun testMultipleTaskConfigurations() {
        // Configure different models for different tasks
        val firstMessageModel = AIModelConfig(
            modelName = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = AIProvider.GOOGLE_AI
        )
        
        val followupModel = AIModelConfig(
            modelName = "gemini-2.5-flash-lite",
            displayName = "Gemini 2.5 Flash Lite",
            provider = AIProvider.GOOGLE_AI
        )
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, firstMessageModel)
        AIConfigManager.setModelForTask(context, AITask.FOLLOWUP_NO_RESPONSE, followupModel)
        
        // Verify both are stored correctly
        assertEquals("gemini-2.5-pro", 
            AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE).modelName)
        assertEquals("gemini-2.5-flash-lite", 
            AIConfigManager.getModelForTask(context, AITask.FOLLOWUP_NO_RESPONSE).modelName)
    }

    @Test
    fun testGetAllTaskConfigurations() {
        // Set configurations for multiple tasks
        val firstMessageModel = AIModelConfig(
            modelName = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = AIProvider.GOOGLE_AI
        )
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, firstMessageModel)
        
        // Get all configurations
        val allConfigs = AIConfigManager.getAllTaskConfigurations(context)
        
        assertTrue(allConfigs.containsKey(AITask.FIRST_MESSAGE))
        assertEquals(firstMessageModel.modelName, allConfigs[AITask.FIRST_MESSAGE]?.modelName)
    }

    @Test
    fun testGetAvailableModels() {
        // Should return list of available models
        val models = AIConfigManager.getAvailableModels()
        
        assertTrue(models.isNotEmpty())
        assertTrue(models.any { it.modelName == "gemini-2.5-pro" })
        assertTrue(models.any { it.provider == AIProvider.GOOGLE_AI })
    }

    @Test
    fun testResetToDefaults() {
        // Set custom configurations
        val customModel = AIModelConfig(
            modelName = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = AIProvider.GOOGLE_AI
        )
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, customModel)
        assertEquals("gemini-2.5-flash", 
            AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE).modelName)
        
        // Reset to defaults
        AIConfigManager.resetToDefaults(context)
        
        // Should return default model
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-pro", config.modelName)
    }

    @Test
    fun testExportConfiguration() {
        // Set configurations
        val firstMessageModel = AIModelConfig(
            modelName = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = AIProvider.GOOGLE_AI
        )
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, firstMessageModel)
        
        // Export to JSON
        val json = AIConfigManager.exportConfiguration(context)
        
        assertNotNull(json)
        assertTrue(json.contains("FIRST_MESSAGE"))
        assertTrue(json.contains("gemini-2.5-pro"))
    }

    @Test
    fun testImportConfiguration() {
        // Create JSON configuration
        val json = """
        {
            "FIRST_MESSAGE": {
                "modelName": "gemini-2.5-flash",
                "displayName": "Gemini 2.5 Flash",
                "provider": "GOOGLE_AI"
            }
        }
        """.trimIndent()
        
        // Import
        val success = AIConfigManager.importConfiguration(context, json)
        assertTrue(success)
        
        // Verify imported configuration
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-flash", config.modelName)
    }

    @Test
    fun testInvalidJsonImport() {
        val invalidJson = "{ invalid json }"
        
        val success = AIConfigManager.importConfiguration(context, invalidJson)
        assertFalse(success)
        
        // Should still return default configuration after failed import
        val config = AIConfigManager.getModelForTask(context, AITask.CONVERSATION)
        assertNotNull(config)
    }
}
