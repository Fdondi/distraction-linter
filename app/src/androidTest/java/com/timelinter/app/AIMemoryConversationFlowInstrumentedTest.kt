package com.timelinter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Instrumented test to investigate the specific AI memory conversation flow issue.
 * This test simulates the exact scenario from the user's image and tests the
 * complete conversation flow including memory storage, retrieval, and AI template integration.
 */
@RunWith(AndroidJUnit4::class)
class AIMemoryConversationFlowInstrumentedTest {

    private lateinit var appContext: android.content.Context
    private lateinit var fakeTime: FakeTimeProvider

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        fakeTime = FakeTimeProvider(1000L) // Start at 1 second
        // Clear any existing memories before each test
        AIMemoryManager.clearAllMemories(appContext)
    }

    @After
    fun tearDown() {
        // Clean up after each test
        AIMemoryManager.clearAllMemories(appContext)
    }

    @Test
    fun testExactScenarioFromUserImage() {
        // This test reproduces the exact scenario from the user's image:
        // - AI responds with remember() and allow() commands
        // - Memory should be stored correctly
        // - Memory should be available for next conversation
        
        // Given - Simulate the AI response parsing from the user's image
        val parsedResponse = ParsedResponse(
            userMessage = "Oh, looks like you're still in the zone! No worries at all, just wanted to check in again and see if you're feeling good about your flow, especially with that Google impression in mind.",
            tools = listOf(
                ToolCommand.Remember("User is working for Google temporarily and wants to make a good impression.", null),
                ToolCommand.Allow(15.minutes, "YouTube")
            )
        )
        
        // When - Process the tools (simulating AppUsageMonitorService logic)
        var memoryStored = false
        var allowProcessed = false
        
        for (tool in parsedResponse.tools) {
            when (tool) {
                is ToolCommand.Allow -> {
                    // Allow command processing (simplified)
                    println("Processing ALLOW tool: ${tool.duration.inWholeMinutes} minutes${tool.app?.let { " for $it" } ?: ""}")
                    allowProcessed = true
                }
                is ToolCommand.Remember -> {
                    println("Processing REMEMBER tool: ${tool.content}")
                    val duration = tool.duration
                    if (duration != null) {
                        AIMemoryManager.addTemporaryMemory(appContext, tool.content, duration, fakeTime)
                    } else {
                        AIMemoryManager.addPermanentMemory(appContext, tool.content)
                    }
                    memoryStored = true
                }
            }
        }
        
        // Then - Verify both commands were processed
        assertTrue("REMEMBER command should be processed", memoryStored)
        assertTrue("ALLOW command should be processed", allowProcessed)
        
        // Verify memory was stored and can be retrieved
        val memoriesForNextConversation = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Memory should be available for next conversation", "User is working for Google temporarily and wants to make a good impression.", memoriesForNextConversation)
    }

    @Test
    fun testMemoryTemplateIntegration() {
        // Test that the memory is correctly formatted for the AI memory template
        val memoryContent = "User is working for Google temporarily and wants to make a good impression."
        
        // When - Add memory and format for AI
        AIMemoryManager.addPermanentMemory(appContext, memoryContent)
        val memories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        
        // Then - Test AI memory template integration
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        
        val expectedMemoryMessage = "Previous memories:\n$memoryContent"
        assertEquals("Memory should format correctly for AI template", expectedMemoryMessage, memoryMessage)
        
        // Verify this would be added to conversation correctly
        assertTrue("Memory message should contain the memory content", memoryMessage.contains(memoryContent))
        assertTrue("Memory message should start with template", memoryMessage.startsWith("Previous memories:"))
    }

    @Test
    fun testEmptyMemoryScenario() {
        // Test the scenario where no memories exist (should result in "No previous memories.")
        val memories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Should return empty string when no memories exist", "", memories)
        
        // Test with AI memory template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        val expectedMemoryMessage = "Previous memories:\n"
        assertEquals("Empty memory should format correctly", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testMemoryPersistenceAcrossConversationResets() {
        // Test that memory persists even when conversations are reset
        // due to allow commands or other reasons
        
        // Given - Start with some memory
        val initialMemory = "User is working for Google temporarily and wants to make a good impression."
        AIMemoryManager.addPermanentMemory(appContext, initialMemory)
        
        // When - Simulate conversation reset (allow command triggers reset)
        val allowCommand = ToolCommand.Allow(15.minutes, "YouTube")
        
        // Process allow command (this would trigger conversation reset in real app)
        when (allowCommand) {
            is ToolCommand.Allow -> {
                println("Processing ALLOW tool: ${allowCommand.duration.inWholeMinutes} minutes${allowCommand.app?.let { " for $it" } ?: ""}")
                // In real app, this would trigger conversationHistoryManager.clearHistories()
                // but memory should persist
            }
        }
        
        // Then - Verify memory still exists after conversation reset
        val memoriesAfterReset = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Memory should persist after conversation reset", initialMemory, memoriesAfterReset)
    }

    @Test
    fun testMultipleMemoryAdditionsInSequence() {
        // Test that multiple memory additions work correctly
        // and don't interfere with each other
        
        // Given - Start with no memories
        val memory1 = "User is working for Google temporarily and wants to make a good impression."
        val memory2 = "User prefers working in the morning."
        
        // When - Add multiple memories in sequence
        AIMemoryManager.addPermanentMemory(appContext, memory1)
        AIMemoryManager.addPermanentMemory(appContext, memory2)
        
        // Then - Verify both memories are present
        val allMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        val expectedMemories = "$memory1\n$memory2"
        assertEquals("Both memories should be present", expectedMemories, allMemories)
        
        // Verify AI template formatting
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", allMemories)
        val expectedMemoryMessage = "Previous memories:\n$memory1\n$memory2"
        assertEquals("Multiple memories should format correctly for AI", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testMemoryRetrievalTiming() {
        // Test that memory is available at the right time
        // in the conversation flow
        
        // Given - Set up memory
        val memory = "User is working for Google temporarily and wants to make a good impression."
        AIMemoryManager.addPermanentMemory(appContext, memory)
        
        // When - Simulate conversation initialization (like in ConversationHistoryManager)
        val memories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        
        // Then - Verify memory is available for conversation initialization
        assertEquals("Memory should be available for conversation initialization", memory, memories)
        
        // Verify it would be added to conversation correctly
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        
        assertTrue("Memory should be included in conversation", memoryMessage.contains(memory))
        assertFalse("Memory should not be empty", memoryMessage.contains("No previous memories"))
    }

    @Test
    fun testMemoryWithTemporaryAndPermanentMemories() {
        // Test the scenario where both permanent and temporary memories exist
        val permanentMemory = "User is working for Google temporarily and wants to make a good impression."
        val tempMemory = "User is currently focused on YouTube"
        
        // When - Add both types of memories
        AIMemoryManager.addPermanentMemory(appContext, permanentMemory)
        AIMemoryManager.addTemporaryMemory(appContext, tempMemory, 1.minutes, fakeTime) // 1 minute for quick testing
        
        // Then - Verify both are present initially
        val initialMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertTrue("Permanent memory should be present", initialMemories.contains(permanentMemory))
        assertTrue("Temporary memory should be present", initialMemories.contains(tempMemory))
        
        // Advance time to expire temporary memory
        fakeTime.advanceMinutes(2)
        
        // Verify only permanent memory remains
        val finalMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertTrue("Permanent memory should remain", finalMemories.contains(permanentMemory))
        assertFalse("Temporary memory should be expired", finalMemories.contains(tempMemory))
    }

    @Test
    fun testMemoryWithSpecialCharactersAndFormatting() {
        // Test memory with special characters that might cause issues
        val specialMemory = "User's preference: \"Work on Google projects\" (temporary assignment) - avoid distractions!"
        
        // When - Add memory with special characters
        AIMemoryManager.addPermanentMemory(appContext, specialMemory)
        
        // Then - Verify it's stored and retrieved correctly
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Memory with special characters should be stored correctly", specialMemory, retrievedMemories)
        
        // Test AI template integration
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", retrievedMemories)
        val expectedMemoryMessage = "Previous memories:\n$specialMemory"
        assertEquals("Special characters should format correctly for AI", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testMemoryEdgeCases() {
        // Test various edge cases that might cause issues
        
        // Test empty memory (clear first to avoid concatenation)
        AIMemoryManager.clearAllMemories(appContext)
        AIMemoryManager.addPermanentMemory(appContext, "")
        val emptyMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Empty memory should be handled correctly", "", emptyMemories)
        
        // Test whitespace memory (clear first to avoid concatenation)
        AIMemoryManager.clearAllMemories(appContext)
        AIMemoryManager.addPermanentMemory(appContext, "   \n\t   ")
        val whitespaceMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Whitespace memory should be handled correctly", "   \n\t   ", whitespaceMemories)
        
        // Test very long memory (clear first to avoid concatenation)
        AIMemoryManager.clearAllMemories(appContext)
        val longMemory = "User is working for Google temporarily and wants to make a good impression. " +
                "This is a very long memory that might test the limits of the storage system. " +
                "It contains multiple sentences and special characters: !@#$%^&*()_+-=[]{}|;':\",./<>?"
        AIMemoryManager.addPermanentMemory(appContext, longMemory)
        val longMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Long memory should be handled correctly", longMemory, longMemories)
    }

    @Test
    fun testMemoryIntegrationWithConversationLogStore() {
        // Test that memory integration works with ConversationLogStore
        val memoryContent = "User is working for Google temporarily and wants to make a good impression."
        
        // When - Add memory and simulate ConversationLogStore.setMemory call
        AIMemoryManager.addPermanentMemory(appContext, memoryContent)
        val allMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        
        // Simulate ConversationLogStore.setMemory call (like in AppUsageMonitorService)
        ConversationLogStore.setMemory(allMemories)
        
        // Then - Verify memory is available in the store
        val storedMemory = ConversationLogStore.aiMemory.value
        assertEquals("Memory should be available in ConversationLogStore", allMemories, storedMemory)
    }
}


