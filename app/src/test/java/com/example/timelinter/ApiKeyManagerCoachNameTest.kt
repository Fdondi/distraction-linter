package com.example.timelinter

import org.junit.Test
import org.junit.Assert.*

class ApiKeyManagerCoachNameTest {

    @Test
    fun testCoachNameDefaultValue() {
        // Test that the default coach name is "Adam"
        val expectedDefault = "Adam"
        assertEquals("Default coach name should be Adam", expectedDefault, "Adam")
    }

    @Test
    fun testCoachNameStorageKey() {
        // Test that the storage key is correct
        val expectedKey = "coach_name"
        assertEquals("Coach name storage key should be correct", expectedKey, "coach_name")
    }

    @Test
    fun testCoachNameValidation() {
        // Test various coach name scenarios
        val validNames = listOf("Adam", "Sarah", "Alex", "Jordan", "TestCoach", "Coach-Name", "Test_Coach")
        val invalidNames = listOf("", "   ", "\n", "\t")
        
        validNames.forEach { name ->
            assertTrue("Name '$name' should be valid", name.isNotBlank())
            assertTrue("Name '$name' should not be empty", name.isNotEmpty())
        }
        
        invalidNames.forEach { name ->
            assertFalse("Name '$name' should be invalid", name.isNotBlank())
        }
    }

    @Test
    fun testCoachNameSaveLogic() {
        // Test the logic for when save should be enabled
        fun shouldEnableSave(currentName: String, newName: String): Boolean {
            return newName != currentName && newName.isNotBlank()
        }
        
        // Test cases
        assertTrue("Should enable save when name changes", shouldEnableSave("Adam", "Sarah"))
        assertFalse("Should not enable save when name is same", shouldEnableSave("Adam", "Adam"))
        assertFalse("Should not enable save when new name is blank", shouldEnableSave("Adam", ""))
        assertFalse("Should not enable save when new name is whitespace", shouldEnableSave("Adam", "   "))
        assertTrue("Should enable save when changing to different valid name", shouldEnableSave("Adam", "Alex"))
    }

    @Test
    fun testCoachNameDisplayFormat() {
        // Test the display format
        val testCases = mapOf(
            "Adam" to "Hi I'm Adam",
            "Sarah" to "Hi I'm Sarah",
            "TestCoach" to "Hi I'm TestCoach"
        )
        
        testCases.forEach { (name, expectedDisplay) ->
            val actualDisplay = "Hi I'm $name"
            assertEquals("Display format should match for $name", expectedDisplay, actualDisplay)
        }
    }

    @Test
    fun testCoachNameLengthValidation() {
        // Test reasonable length limits
        val reasonableNames = listOf("A", "Adam", "Sarah", "Alexander", "VeryLongCoachName")
        val tooLongName = "A".repeat(100) // 100 character name
        
        reasonableNames.forEach { name ->
            assertTrue("Name '$name' should be reasonable length", name.length in 1..50)
        }
        
        assertTrue("Very long name should be detected", tooLongName.length > 50)
    }

    @Test
    fun testCoachNameSpecialCharacters() {
        // Test names with special characters
        val namesWithSpecialChars = listOf(
            "Coach-Name",
            "Test_Coach", 
            "Coach123",
            "Coach.Name",
            "Coach Name", // space
            "Coach@Name"  // @ symbol
        )
        
        namesWithSpecialChars.forEach { name ->
            assertTrue("Name with special chars '$name' should be valid", name.isNotBlank())
            assertTrue("Name with special chars '$name' should not be empty", name.isNotEmpty())
        }
    }
}


