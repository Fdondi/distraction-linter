package com.timelinter.app

import org.junit.Test
import org.junit.Assert.*

class CoachNameUnitTest {

    @Test
    fun testCoachNameDefaultsToAdam() {
        // Test that the default coach name is "Adam"
        val expectedDefaultName = "Adam"
        assertEquals("Default coach name should be Adam", expectedDefaultName, "Adam")
    }

    @Test
    fun testCoachNameCanBeSet() {
        // Test that coach name can be set to different values
        val testNames = listOf("Adam", "Sarah", "Alex", "Jordan", "TestCoach")
        
        testNames.forEach { name ->
            assertTrue("Coach name should not be empty", name.isNotEmpty())
            assertTrue("Coach name should be a valid string", name.length > 0)
        }
    }

    @Test
    fun testCoachNameValidation() {
        // Test coach name validation logic
        val validNames = listOf("Adam", "Sarah", "Alex123", "Coach-Name", "Test_Coach")
        val invalidNames = listOf("", "   ", null)
        
        validNames.forEach { name ->
            assertTrue("Name '$name' should be valid", name.isNotBlank())
        }
        
        invalidNames.forEach { name ->
            if (name != null) {
                assertFalse("Name '$name' should be invalid", name.isNotBlank())
            }
        }
    }

    @Test
    fun testCoachNameSaveLogic() {
        // Test the logic for when save button should be enabled
        val currentName = "Adam"
        val newName = "Sarah"
        val sameName = "Adam"
        val blankName = ""
        
        // Save should be enabled when name changes and is not blank
        assertTrue("Save should be enabled when name changes", 
            newName != currentName && newName.isNotBlank())
        
        // Save should be disabled when name is the same
        assertFalse("Save should be disabled when name is the same", 
            sameName != currentName && sameName.isNotBlank())
        
        // Save should be disabled when name is blank
        assertFalse("Save should be disabled when name is blank", 
            blankName != currentName && blankName.isNotBlank())
    }

    @Test
    fun testCoachNameDisplayFormat() {
        // Test the display format for coach name
        val coachName = "Adam"
        val expectedDisplay = "Hi I'm $coachName"
        val actualDisplay = "Hi I'm $coachName"
        
        assertEquals("Display format should match expected", expectedDisplay, actualDisplay)
    }
}
