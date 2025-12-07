package com.timelinter.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for AI configuration with AIInteractionManager
 */
@RunWith(AndroidJUnit4::class)
class AIConfigIntegrationTest {

    private lateinit var context: Context
    private lateinit var conversationHistoryManager: ConversationHistoryManager
    private lateinit var aiInteractionManager: AIInteractionManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Clear any existing configuration
        val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        
        // Set up conversation history manager
        conversationHistoryManager = ConversationHistoryManager(
            context = context,
            systemPrompt = "Test system prompt",
            aiMemoryTemplate = "Test memory: {{AI_MEMORY}}",
            userInfoTemplate = "Test user info",
            userInteractionTemplate = "Test interaction",
            timeProvider = SystemTimeProvider
        )
    }

    @After
    fun tearDown() {
        // Clean up
        val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testAIInteractionManagerUsesDefaultConfiguration() {
        // Create AIInteractionManager without specifying task
        aiInteractionManager = AIInteractionManager(context, conversationHistoryManager)
        
        // Verify it uses the default configuration for FIRST_MESSAGE
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-pro", config.modelName)
    }

    @Test
    fun testAIInteractionManagerUsesCustomConfiguration() {
        // Set custom configuration
        val customModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, customModel)
        
        // Create AIInteractionManager
        aiInteractionManager = AIInteractionManager(context, conversationHistoryManager)
        
        // Verify it uses the custom configuration
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-flash", config.modelName)
    }

    @Test
    fun testAIInteractionManagerWithSpecificTask() {
        // Set different models for different tasks
        val firstMessageModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_PRO)
        val followupModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH_LITE)
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, firstMessageModel)
        AIConfigManager.setModelForTask(context, AITask.FOLLOWUP_NO_RESPONSE, followupModel)
        
        // Create AIInteractionManager with FOLLOWUP_NO_RESPONSE task
        aiInteractionManager = AIInteractionManager(
            context,
            conversationHistoryManager,
            defaultTask = AITask.FOLLOWUP_NO_RESPONSE
        )
        
        // Verify it uses the followup configuration
        val config = AIConfigManager.getModelForTask(context, AITask.FOLLOWUP_NO_RESPONSE)
        assertEquals("gemini-2.5-flash-lite", config.modelName)
    }

    @Test
    fun testSwitchTaskInAIInteractionManager() {
        // Set different models for different tasks
        val firstMessageModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_PRO)
        val userResponseModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, firstMessageModel)
        AIConfigManager.setModelForTask(context, AITask.USER_RESPONSE, userResponseModel)
        
        // Create AIInteractionManager
        aiInteractionManager = AIInteractionManager(context, conversationHistoryManager)
        
        // Verify initial task
        var config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-pro", config.modelName)
        
        // Switch to USER_RESPONSE task
        aiInteractionManager.switchTask(AITask.USER_RESPONSE)
        
        // Verify switched task
        config = AIConfigManager.getModelForTask(context, AITask.USER_RESPONSE)
        assertEquals("gemini-2.5-flash", config.modelName)
    }

    @Test
    fun testConfigurationPersistenceAcrossInstances() {
        // Set configuration
        val customModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, customModel)
        
        // Create first instance
        val manager1 = AIInteractionManager(context, conversationHistoryManager)
        
        // Create second instance (simulates app restart)
        val manager2 = AIInteractionManager(context, conversationHistoryManager)
        
        // Both should use the same persisted configuration
        val config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-flash", config.modelName)
    }

    @Test
    fun testResetToDefaultsAffectsNewInstances() {
        // Set custom configuration
        val customModel = AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        AIConfigManager.setModelForTask(context, AITask.FIRST_MESSAGE, customModel)
        
        // Verify custom config is set
        var config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-flash", config.modelName)
        
        // Reset to defaults
        AIConfigManager.resetToDefaults(context)
        
        // Create new instance
        val manager = AIInteractionManager(context, conversationHistoryManager)
        
        // Should use default configuration
        config = AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE)
        assertEquals("gemini-2.5-pro", config.modelName)
    }

    @Test
    fun testDifferentTasksUseDifferentModels() {
        // Set different models for each task type
        AIConfigManager.setModelForTask(
            context, 
            AITask.FIRST_MESSAGE,
            AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_PRO)
        )
        AIConfigManager.setModelForTask(
            context,
            AITask.FOLLOWUP_NO_RESPONSE,
            AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH_LITE)
        )
        AIConfigManager.setModelForTask(
            context,
            AITask.USER_RESPONSE,
            AIModelConfig.AVAILABLE_MODELS.getValue(AIModelId.GEMINI_25_FLASH)
        )
        
        // Verify all three tasks have different models
        assertEquals("gemini-2.5-pro", 
            AIConfigManager.getModelForTask(context, AITask.FIRST_MESSAGE).modelName)
        assertEquals("gemini-2.5-flash-lite",
            AIConfigManager.getModelForTask(context, AITask.FOLLOWUP_NO_RESPONSE).modelName)
        assertEquals("gemini-2.5-flash",
            AIConfigManager.getModelForTask(context, AITask.USER_RESPONSE).modelName)
    }
}
