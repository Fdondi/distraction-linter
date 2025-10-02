package com.example.timelinter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick test to verify AI memory functionality is working.
 * This is a simple test that can be run quickly to check if the basic
 * memory storage and retrieval is working correctly.
 */
@RunWith(AndroidJUnit4::class)
class AIMemoryQuickTest {

    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear any existing memories before each test
        AIMemoryManager.clearAllMemories(appContext)
    }

    @After
    fun tearDown() {
        // Clean up after each test
        AIMemoryManager.clearAllMemories(appContext)
    }

    @Test
    fun testBasicMemoryStorageAndRetrieval() {
        // Test the most basic memory functionality
        val testMemory = "User is working for Google temporarily and wants to make a good impression."
        
        // When - Add memory
        AIMemoryManager.addPermanentMemory(appContext, testMemory)
        
        // Then - Verify it can be retrieved
        val retrievedMemory = AIMemoryManager.getAllMemories(appContext)
        assertEquals("Memory should be stored and retrieved correctly", testMemory, retrievedMemory)
    }

    @Test
    fun testMemoryTemplateIntegration() {
        // Test that memory works with the AI memory template
        val testMemory = "User is working for Google temporarily and wants to make a good impression."
        
        // When - Add memory and format for AI
        AIMemoryManager.addPermanentMemory(appContext, testMemory)
        val memories = AIMemoryManager.getAllMemories(appContext)
        
        // Then - Test AI memory template integration
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        
        val expectedMemoryMessage = "Previous memories:\n$testMemory"
        assertEquals("Memory should format correctly for AI template", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testEmptyMemoryScenario() {
        // Test the scenario where no memories exist
        val memories = AIMemoryManager.getAllMemories(appContext)
        assertEquals("Should return empty string when no memories exist", "", memories)
        
        // Test with AI memory template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        val expectedMemoryMessage = "Previous memories:\n"
        assertEquals("Empty memory should format correctly", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testToolCommandRememberProcessing() {
        // Test the ToolCommand.Remember processing
        val rememberCommand = ToolCommand.Remember(
            content = "User is working for Google temporarily and wants to make a good impression.",
            durationMinutes = null // null means permanent
        )
        
        // When - Process the remember command
        when (rememberCommand) {
            is ToolCommand.Remember -> {
                val durationMinutes = rememberCommand.durationMinutes
                if (durationMinutes != null) {
                    AIMemoryManager.addTemporaryMemory(appContext, rememberCommand.content, durationMinutes)
                } else {
                    AIMemoryManager.addPermanentMemory(appContext, rememberCommand.content)
                }
            }
        }
        
        // Then - Verify memory was stored
        val retrievedMemory = AIMemoryManager.getAllMemories(appContext)
        assertEquals("ToolCommand.Remember should store memory correctly", rememberCommand.content, retrievedMemory)
    }
}


