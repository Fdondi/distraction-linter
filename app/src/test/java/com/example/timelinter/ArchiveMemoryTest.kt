package com.example.timelinter

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the conversation archive memory extraction feature.
 * Ensures that the archive prompt uses proper function calls instead of magic strings.
 */
class ArchiveMemoryTest {

    @Test
    fun testArchivePromptWithMemoryToSave() {
        // Given - AI responds with remember() function call
        val aiResponse = """
            Based on this conversation, there are a few important things to remember:
            remember("User prefers to focus on work between 9am-12pm")
            remember("User gets distracted by social media in the afternoon")
        """.trimIndent()
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should have extracted 2 remember calls
        assertEquals("Should have 2 remember calls", 2, parsedResponse.tools.size)
        assertTrue("First should be Remember", parsedResponse.tools[0] is ToolCommand.Remember)
        assertTrue("Second should be Remember", parsedResponse.tools[1] is ToolCommand.Remember)
        
        val first = parsedResponse.tools[0] as ToolCommand.Remember
        val second = parsedResponse.tools[1] as ToolCommand.Remember
        
        assertEquals("First memory content", "User prefers to focus on work between 9am-12pm", first.content)
        assertEquals("Second memory content", "User gets distracted by social media in the afternoon", second.content)
        assertNull("Should be permanent by default", first.durationMinutes)
        assertNull("Should be permanent by default", second.durationMinutes)
    }

    @Test
    fun testArchivePromptWithNoNewMemory() {
        // Given - AI responds without any function calls
        val aiResponse = """
            I've reviewed the conversation. Everything discussed was already covered in your existing memories, 
            so there's nothing new to add.
        """.trimIndent()
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should have no function calls
        assertEquals("Should have no tools", 0, parsedResponse.tools.size)
        assertTrue("Should have user message", parsedResponse.userMessage.isNotEmpty())
    }

    @Test
    fun testArchivePromptWithTemporaryMemory() {
        // Given - AI responds with temporary memory
        val aiResponse = """
            The user mentioned they're on vacation this week, worth noting:
            remember("User is on vacation", 10080)
        """.trimIndent()
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should have 1 temporary remember call
        assertEquals("Should have 1 remember call", 1, parsedResponse.tools.size)
        
        val rememberTool = parsedResponse.tools[0] as ToolCommand.Remember
        assertEquals("Memory content", "User is on vacation", rememberTool.content)
        assertEquals("Should be temporary for 1 week", 10080, rememberTool.durationMinutes)
    }

    @Test
    fun testArchivePromptMixedPermanentAndTemporary() {
        // Given - AI responds with both permanent and temporary memories
        val aiResponse = """
            I found a few things to remember:
            remember("User has a new hobby: painting")
            remember("User is working on a deadline this week", 10080)
            remember("User prefers evening notifications")
        """.trimIndent()
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should have 3 remember calls
        assertEquals("Should have 3 remember calls", 3, parsedResponse.tools.size)
        
        val first = parsedResponse.tools[0] as ToolCommand.Remember
        val second = parsedResponse.tools[1] as ToolCommand.Remember
        val third = parsedResponse.tools[2] as ToolCommand.Remember
        
        assertEquals("First is permanent", null, first.durationMinutes)
        assertEquals("Second is temporary", 10080, second.durationMinutes)
        assertEquals("Third is permanent", null, third.durationMinutes)
    }

    @Test
    fun testArchivePromptWithMalformedResponse() {
        // Given - AI response with text but no valid function calls
        val aiResponse = "Nothing to remember"
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should gracefully handle with no tools
        assertEquals("Should have no tools", 0, parsedResponse.tools.size)
        assertEquals("Should have user message", "Nothing to remember", parsedResponse.userMessage.trim())
    }

    @Test
    fun testArchivePromptPreservesUserMessageWithFunctionCalls() {
        // Given - AI responds with both text and function calls
        val aiResponse = """
            I found some important patterns in this conversation:
            remember("User works best in the morning")
            I've saved this for future reference.
        """.trimIndent()
        
        // When - Parse the response
        val parsedResponse = parseAIResponse(aiResponse)
        
        // Then - Should have both tools and user message
        assertEquals("Should have 1 remember call", 1, parsedResponse.tools.size)
        assertTrue("Should have user message", parsedResponse.userMessage.isNotEmpty())
        assertTrue("User message should contain explanation", 
            parsedResponse.userMessage.contains("important patterns"))
    }

    @Test
    fun testNoMagicStrings() {
        // This test ensures we never check for magic strings like "No new memory"
        // The test is conceptual - it documents the correct pattern
        
        val responseWithNoMemory = "Nothing new to remember from this conversation."
        val parsedNoMemory = parseAIResponse(responseWithNoMemory)
        
        val responseWithMemory = """remember("User prefers dark mode")"""
        val parsedWithMemory = parseAIResponse(responseWithMemory)
        
        // The difference should be in the presence of tools, NOT in parsing strings
        assertEquals("No memory = no tools", 0, parsedNoMemory.tools.size)
        assertEquals("Has memory = has tools", 1, parsedWithMemory.tools.size)
        
        // IMPORTANT: We should NEVER check for specific text like "No new memory"
        // The presence or absence of function calls is the deterministic signal
        assertFalse("Should not rely on magic strings", 
            parsedNoMemory.userMessage.contains("No new memory", ignoreCase = true))
    }

    @Test
    fun testArchiveFlowLogic() {
        // Test the logic flow for processing archive responses
        // This simulates the AppUsageMonitorService logic
        
        // Case 1: Response with memory
        val responseWithMemory = """remember("User likes breaks every hour")"""
        val parsed1 = parseAIResponse(responseWithMemory)
        
        var memoryAdded = false
        for (tool in parsed1.tools) {
            if (tool is ToolCommand.Remember) {
                memoryAdded = true
                assertEquals("Should add correct content", "User likes breaks every hour", tool.content)
            }
        }
        assertTrue("Should have added memory", memoryAdded)
        
        // Case 2: Response without memory
        val responseNoMemory = "All important information was already in memory."
        val parsed2 = parseAIResponse(responseNoMemory)
        
        var memoryAddedCase2 = false
        for (tool in parsed2.tools) {
            if (tool is ToolCommand.Remember) {
                memoryAddedCase2 = true
            }
        }
        assertFalse("Should not have added memory", memoryAddedCase2)
    }

    @Test
    fun testEmptyResponseHandling() {
        // Edge case: empty response
        val emptyResponse = ""
        val parsed = parseAIResponse(emptyResponse)
        
        assertEquals("Empty response should have no tools", 0, parsed.tools.size)
        assertEquals("Empty response should have empty message", "", parsed.userMessage.trim())
    }

    @Test
    fun testWhitespaceOnlyResponse() {
        // Edge case: whitespace-only response
        val whitespaceResponse = "   \n\t   "
        val parsed = parseAIResponse(whitespaceResponse)
        
        assertEquals("Whitespace response should have no tools", 0, parsed.tools.size)
    }
}

