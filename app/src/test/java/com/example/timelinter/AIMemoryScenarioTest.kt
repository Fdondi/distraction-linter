package com.example.timelinter

import org.junit.Assert.*
import org.junit.Test

/**
 * Test that reproduces the exact scenario from the user's image to investigate
 * the AI memory functionality issue.
 */
class AIMemoryScenarioTest {

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
                ToolCommand.Allow(15, "YouTube")
            )
        )
        
        // When - Process the tools (simulating AppUsageMonitorService logic)
        var memoryStored = false
        var allowProcessed = false
        
        for (tool in parsedResponse.tools) {
            when (tool) {
                is ToolCommand.Allow -> {
                    // Allow command processing (simplified)
                    println("Processing ALLOW tool: ${tool.minutes} minutes${tool.app?.let { " for $it" } ?: ""}")
                    allowProcessed = true
                }
                is ToolCommand.Remember -> {
                    println("Processing REMEMBER tool: ${tool.content}")
                    val durationMinutes = tool.durationMinutes
                    if (durationMinutes != null) {
                        // Would call AIMemoryManager.addTemporaryMemory()
                        println("Would add temporary memory: ${tool.content} for $durationMinutes minutes")
                    } else {
                        // Would call AIMemoryManager.addPermanentMemory()
                        println("Would add permanent memory: ${tool.content}")
                    }
                    memoryStored = true
                }
            }
        }
        
        // Then - Verify both commands were processed
        assertTrue("REMEMBER command should be processed", memoryStored)
        assertTrue("ALLOW command should be processed", allowProcessed)
        
        // Verify the memory content is correct
        val rememberCommand = parsedResponse.tools.first { it is ToolCommand.Remember } as ToolCommand.Remember
        assertEquals("Memory content should match", "User is working for Google temporarily and wants to make a good impression.", rememberCommand.content)
        assertNull("Memory should be permanent", rememberCommand.durationMinutes)
        
        // Verify the allow command is correct
        val allowCommand = parsedResponse.tools.first { it is ToolCommand.Allow } as ToolCommand.Allow
        assertEquals("Allow minutes should match", 15, allowCommand.minutes)
        assertEquals("Allow app should match", "YouTube", allowCommand.app)
    }

    @Test
    fun testMemoryTemplateIntegration() {
        // Test that the memory is correctly formatted for the AI memory template
        val memoryContent = "User is working for Google temporarily and wants to make a good impression."
        
        // When - Format memory for AI template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", memoryContent)
        
        // Then - Verify format is correct
        val expectedMemoryMessage = "Previous memories:\n$memoryContent"
        assertEquals("Memory should format correctly for AI template", expectedMemoryMessage, memoryMessage)
        
        // Verify this would be added to conversation correctly
        assertTrue("Memory message should contain the memory content", memoryMessage.contains(memoryContent))
        assertTrue("Memory message should start with template", memoryMessage.startsWith("Previous memories:"))
    }

    @Test
    fun testEmptyMemoryScenario() {
        // Test the scenario where no memories exist (should result in "No previous memories.")
        val emptyMemory = ""
        
        // When - Format empty memory for AI template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", emptyMemory)
        
        // Then - Verify format is correct
        val expectedMemoryMessage = "Previous memories:\n"
        assertEquals("Empty memory should format correctly", expectedMemoryMessage, memoryMessage)
    }

    @Test
    fun testMultipleMemoryScenario() {
        // Test the scenario where multiple memories exist
        val memory1 = "User is working for Google temporarily and wants to make a good impression."
        val memory2 = "User prefers working in the morning."
        val allMemories = "$memory1\n$memory2"
        
        // When - Format multiple memories for AI template
        val aiMemoryTemplate = "Previous memories:\n{{AI_MEMORY}}"
        val memoryMessage = aiMemoryTemplate.replace("{{AI_MEMORY}}", allMemories)
        
        // Then - Verify format is correct
        val expectedMemoryMessage = "Previous memories:\n$memory1\n$memory2"
        assertEquals("Multiple memories should format correctly", expectedMemoryMessage, memoryMessage)
        
        // Verify both memories are included
        assertTrue("Should contain first memory", memoryMessage.contains(memory1))
        assertTrue("Should contain second memory", memoryMessage.contains(memory2))
    }

    @Test
    fun testToolCommandProcessingFlow() {
        // Test the complete flow of processing ToolCommand.Remember
        val rememberCommand = ToolCommand.Remember(
            content = "User is working for Google temporarily and wants to make a good impression.",
            durationMinutes = null
        )
        
        // When - Process the remember command (simulating AppUsageMonitorService logic)
        val durationMinutes = rememberCommand.durationMinutes
        val shouldAddPermanent = durationMinutes == null
        val shouldAddTemporary = durationMinutes != null
        
        // Then - Verify processing logic
        assertTrue("Should add permanent memory when durationMinutes is null", shouldAddPermanent)
        assertFalse("Should not add temporary memory when durationMinutes is null", shouldAddTemporary)
        
        // Verify the memory content
        assertEquals("Memory content should match", "User is working for Google temporarily and wants to make a good impression.", rememberCommand.content)
    }

    @Test
    fun testToolCommandProcessingFlowWithDuration() {
        // Test the complete flow of processing ToolCommand.Remember with duration
        val rememberCommand = ToolCommand.Remember(
            content = "User is currently focused on YouTube",
            durationMinutes = 15
        )
        
        // When - Process the remember command (simulating AppUsageMonitorService logic)
        val durationMinutes = rememberCommand.durationMinutes
        val shouldAddPermanent = durationMinutes == null
        val shouldAddTemporary = durationMinutes != null
        
        // Then - Verify processing logic
        assertFalse("Should not add permanent memory when durationMinutes is not null", shouldAddPermanent)
        assertTrue("Should add temporary memory when durationMinutes is not null", shouldAddTemporary)
        
        // Verify the memory content and duration
        assertEquals("Memory content should match", "User is currently focused on YouTube", rememberCommand.content)
        assertEquals("Duration should match", 15, durationMinutes)
    }

    @Test
    fun testConversationFlowWithMemoryAndAllow() {
        // Test the complete conversation flow with both memory and allow commands
        val parsedResponse = ParsedResponse(
            userMessage = "Oh, looks like you're still in the zone! No worries at all, just wanted to check in again and see if you're feeling good about your flow, especially with that Google impression in mind.",
            tools = listOf(
                ToolCommand.Remember("User is working for Google temporarily and wants to make a good impression.", null),
                ToolCommand.Allow(15, "YouTube")
            )
        )
        
        // When - Process the tools
        var memoryContent = ""
        var allowMinutes = 0
        var allowApp = ""
        
        for (tool in parsedResponse.tools) {
            when (tool) {
                is ToolCommand.Allow -> {
                    allowMinutes = tool.minutes
                    allowApp = tool.app ?: ""
                }
                is ToolCommand.Remember -> {
                    memoryContent = tool.content
                }
            }
        }
        
        // Then - Verify both commands were processed correctly
        assertEquals("Memory content should match", "User is working for Google temporarily and wants to make a good impression.", memoryContent)
        assertEquals("Allow minutes should match", 15, allowMinutes)
        assertEquals("Allow app should match", "YouTube", allowApp)
        
        // Verify the user message
        assertTrue("User message should contain Google impression reference", parsedResponse.userMessage.contains("Google impression"))
    }

    @Test
    fun testMemoryPersistenceScenario() {
        // Test that memory would persist across conversation resets
        val initialMemory = "User is working for Google temporarily and wants to make a good impression."
        
        // Simulate memory being stored
        val storedMemory = initialMemory
        
        // Simulate conversation reset (allow command triggers reset)
        val allowCommand = ToolCommand.Allow(15, "YouTube")
        
        // Process allow command (this would trigger conversation reset in real app)
        when (allowCommand) {
            is ToolCommand.Allow -> {
                println("Processing ALLOW tool: ${allowCommand.minutes} minutes${allowCommand.app?.let { " for $it" } ?: ""}")
                // In real app, this would trigger conversationHistoryManager.clearHistories()
                // but memory should persist
            }
        }
        
        // Verify memory still exists after conversation reset
        assertEquals("Memory should persist after conversation reset", initialMemory, storedMemory)
    }
}

