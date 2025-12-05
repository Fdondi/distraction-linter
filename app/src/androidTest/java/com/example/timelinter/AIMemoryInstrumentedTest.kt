package com.example.timelinter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Instrumented test to investigate AI memory functionality issues.
 * This test runs on the actual Android device/emulator and tests the real
 * SharedPreferences storage and retrieval.
 */
@RunWith(AndroidJUnit4::class)
class AIMemoryInstrumentedTest {

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
    fun testPermanentMemoryStorageAndRetrieval() {
        // Test permanent memory storage - use a truly permanent fact
        val memoryContent = "User wants to make a good impression at work."
        
        // When - Add permanent memory
        AIMemoryManager.addPermanentMemory(appContext, memoryContent)
        
        // Then - Verify it can be retrieved
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Permanent memory should be retrievable", memoryContent, retrievedMemories)
    }

    @Test
    fun testMultiplePermanentMemories() {
        // Test multiple permanent memories
        val memory1 = "User wants to make a good impression at work."
        val memory2 = "User prefers working in the morning."
        
        // When - Add multiple memories
        AIMemoryManager.addPermanentMemory(appContext, memory1)
        AIMemoryManager.addPermanentMemory(appContext, memory2)
        
        // Then - Verify both are present
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        val expectedMemories = "$memory1\n$memory2"
        assertEquals("Multiple permanent memories should be stored", expectedMemories, retrievedMemories)
    }

    @Test
    fun testTemporaryMemoryWithExpiration() {
        // Test temporary memory that expires
        val tempMemory = "User is currently focused on YouTube"
        val duration = 1.minutes // 1 minute for quick testing
        
        // When - Add temporary memory
        AIMemoryManager.addTemporaryMemory(appContext, tempMemory, duration, fakeTime)
        
        // Then - Verify it's present initially
        val initialMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertTrue("Temporary memory should be present initially", initialMemories.contains(tempMemory))
        
        // Advance time beyond expiration
        fakeTime.advanceMinutes(2)
        
        // Verify it's expired and cleaned up
        val expiredMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertFalse("Temporary memory should be expired", expiredMemories.contains(tempMemory))
    }

    @Test
    fun testMixedPermanentAndTemporaryMemories() {
        // Test both permanent and temporary memories together
        val permanentMemory = "User wants to make a good impression at work."
        val tempMemory = "User is currently focused on YouTube"
        
        // When - Add both types
        AIMemoryManager.addPermanentMemory(appContext, permanentMemory)
        AIMemoryManager.addTemporaryMemory(appContext, tempMemory, 1.minutes, fakeTime)
        
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
    fun testMemoryFormatForAITemplate() {
        // Test that memory format works correctly with AI memory template
        val memoryContent = "User wants to make a good impression at work."
        
        // When - Add memory and get it formatted
        AIMemoryManager.addPermanentMemory(appContext, memoryContent)
        val memories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        
        // Then - Test AI template integration
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val formattedForAI = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        val expectedFormat = "Previous memories:\n$memoryContent"
        
        assertEquals("Memory should format correctly for AI template", expectedFormat, formattedForAI)
    }

    @Test
    fun testEmptyMemoryScenario() {
        // Test the scenario where no memories exist (should result in "No previous memories.")
        val memories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Should return empty string when no memories exist", "", memories)
        
        // Test with AI template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val formattedForAI = aiMemoryTemplate.replace("{{AI_MEMORY}}", memories)
        val expectedFormat = "Previous memories:\n"
        assertEquals("Empty memory should format correctly", expectedFormat, formattedForAI)
    }

    @Test
    fun testToolCommandRememberProcessing() {
        // Test the exact ToolCommand.Remember processing from the user's scenario
        // Note: "temporarily" should use temporary memory with a duration
        val rememberCommand = ToolCommand.Remember(
            content = "User is working for Google and wants to make a good impression.",
            duration = null // null means permanent
        )
        
        // When - Process the remember command (simulating AppUsageMonitorService logic)
        when (rememberCommand) {
            is ToolCommand.Remember -> {
                val duration = rememberCommand.duration
                if (duration != null) {
                    AIMemoryManager.addTemporaryMemory(appContext, rememberCommand.content, duration, fakeTime)
                } else {
                    AIMemoryManager.addPermanentMemory(appContext, rememberCommand.content)
                }
            }
        }
        
        // Then - Verify memory was stored and can be retrieved
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("ToolCommand.Remember should store memory correctly", rememberCommand.content, retrievedMemories)
    }

    @Test
    fun testMemoryPersistenceAcrossAppRestarts() {
        // Test that memory persists across app restarts (simulated by clearing and re-adding)
        val memoryContent = "User wants to make a good impression at work."
        
        // When - Add memory
        AIMemoryManager.addPermanentMemory(appContext, memoryContent)
        
        // Simulate app restart by creating new context (in real app, this would be a new process)
        val newContext = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Then - Verify memory is still there
        val retrievedMemories = AIMemoryManager.getAllMemories(newContext, fakeTime)
        assertEquals("Memory should persist across app restarts", memoryContent, retrievedMemories)
    }

    @Test
    fun testMemoryCleanupAfterExpiration() {
        // Test that expired memories are automatically cleaned up
        val tempMemory1 = "User is currently focused on YouTube"
        val tempMemory2 = "User is in a meeting"
        
        // Add first temporary memory
        AIMemoryManager.addTemporaryMemory(appContext, tempMemory1, 1.minutes, fakeTime) // Expires in 1 minute
        
        // Advance time slightly to ensure different timestamps
        fakeTime.advanceMs(100)
        
        // Add second temporary memory
        AIMemoryManager.addTemporaryMemory(appContext, tempMemory2, 2.minutes, fakeTime) // Expires in 2 minutes
        
        // Verify both are present initially
        val initialMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertTrue("First temp memory should be present", initialMemories.contains(tempMemory1))
        assertTrue("Second temp memory should be present", initialMemories.contains(tempMemory2))
        
        // Advance time to expire first memory
        fakeTime.advanceMinutes(1)
        val afterFirstExpiry = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertFalse("First temp memory should be expired", afterFirstExpiry.contains(tempMemory1))
        assertTrue("Second temp memory should still be present", afterFirstExpiry.contains(tempMemory2))
        
        // Advance time to expire second memory
        fakeTime.advanceMinutes(1)
        val afterSecondExpiry = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertFalse("Second temp memory should be expired", afterSecondExpiry.contains(tempMemory2))
    }

    @Test
    fun testMemoryWithSpecialCharacters() {
        // Test memory with special characters that might cause issues
        val specialMemory = "User's preference: \"Work on Google projects\" (temporary assignment) - avoid distractions!"
        
        // When - Add memory with special characters
        AIMemoryManager.addPermanentMemory(appContext, specialMemory)
        
        // Then - Verify it's stored and retrieved correctly
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Memory with special characters should be stored correctly", specialMemory, retrievedMemories)
    }

    @Test
    fun testMemoryWithEmptyContent() {
        // Test edge case with empty content
        val emptyMemory = ""
        
        // When - Add empty memory
        AIMemoryManager.addPermanentMemory(appContext, emptyMemory)
        
        // Then - Verify it's handled correctly
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Empty memory should be stored as empty string", emptyMemory, retrievedMemories)
    }

    @Test
    fun testMemoryWithWhitespaceContent() {
        // Test edge case with whitespace-only content
        val whitespaceMemory = "   \n\t   "
        
        // When - Add whitespace memory
        AIMemoryManager.addPermanentMemory(appContext, whitespaceMemory)
        
        // Then - Verify it's stored correctly
        val retrievedMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Whitespace memory should be stored correctly", whitespaceMemory, retrievedMemories)
    }

    @Test
    fun testTemporarySituationMemoryShouldUseTemporaryMemory() {
        // Test that temporary situations like "working temporarily" should use temporary memory
        // This demonstrates the CORRECT usage pattern
        val temporaryJobMemory = "User is working for Google temporarily and wants to make a good impression."
        val durationInDays = 7 // Working temporarily at Google for 1 week
        val durationTotal = (durationInDays * 24 * 60).minutes
        
        // When - Add as TEMPORARY memory (correct approach for temporary situations)
        AIMemoryManager.addTemporaryMemory(appContext, temporaryJobMemory, durationTotal, fakeTime)
        
        // Then - Verify it's present initially
        val initialMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertTrue("Temporary job memory should be present", initialMemories.contains(temporaryJobMemory))
        
        // Advance time beyond the temporary job duration
        fakeTime.advance(durationTotal + 1.minutes)
        
        // Verify it's expired and cleaned up
        val expiredMemories = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertFalse("Temporary job memory should expire after the duration", expiredMemories.contains(temporaryJobMemory))
    }

    @Test
    fun testMemoryIntegrationWithConversationFlow() {
        // Test the complete flow from the user's scenario
        // 1. AI responds with remember() and allow() commands
        // 2. Memory should be stored
        // 3. Allow command should not affect memory
        // 4. Next conversation should include the memory
        
        val parsedResponse = ParsedResponse(
            userMessage = "Oh, looks like you're still in the zone! No worries at all, just wanted to check in again and see if you're feeling good about your flow, especially with that work goal in mind.",
            tools = listOf(
                ToolCommand.Remember("User wants to make a good impression at work.", null),
                ToolCommand.Allow(15.minutes, "YouTube")
            )
        )
        
        // When - Process the tools (simulating AppUsageMonitorService logic)
        for (tool in parsedResponse.tools) {
            when (tool) {
                is ToolCommand.Allow -> {
                    // Allow command processing (simplified)
                    println("Processing ALLOW tool: ${tool.duration.inWholeMinutes} minutes${tool.app?.let { " for $it" } ?: ""}")
                }
                is ToolCommand.Remember -> {
                    println("Processing REMEMBER tool: ${tool.content}")
                    val duration = tool.duration
                    if (duration != null) {
                        AIMemoryManager.addTemporaryMemory(appContext, tool.content, duration, fakeTime)
                    } else {
                        AIMemoryManager.addPermanentMemory(appContext, tool.content)
                    }
                }
            }
        }
        
        // Then - Verify memory was stored and can be retrieved for next conversation
        val memoriesForNextConversation = AIMemoryManager.getAllMemories(appContext, fakeTime)
        assertEquals("Memory should be available for next conversation", "User wants to make a good impression at work.", memoriesForNextConversation)
        
        // Verify AI template integration
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memoriesForNextConversation)
        val expectedMemoryMessage = "Previous memories:\nUser wants to make a good impression at work."
        assertEquals("Memory should format correctly for AI", expectedMemoryMessage, memoryMessage)
    }
}


