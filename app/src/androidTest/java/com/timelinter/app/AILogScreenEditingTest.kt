package com.timelinter.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AILogScreenEditingTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivity>()

	@Before
	fun setUp() {
		val context = composeRule.activity
		AIMemoryManager.clearAllMemories(context)
		AIMemoryManager.setPermanentMemory(context, "Initial permanent")
	}

	@After
	fun tearDown() {
		AIMemoryManager.clearAllMemories(composeRule.activity)
	}

	@Test
	fun editMemory_fromAILogTab_updatesPermanentMemory() {
		// Navigate to AI Log screen
		composeRule.onNodeWithContentDescription("AI Log").performClick()

		// Verify initial memory is visible
		composeRule.onNodeWithText("AI Memory").assertExists()
		composeRule.onNodeWithText("Initial permanent").assertExists()

		// Enter edit mode
		composeRule.onNodeWithText("Edit").performClick()

		// Edit the text
		val editor = composeRule.onNodeWithTag("aiMemoryEditor")
		editor.performTextClearance()
		editor.performTextInput("Edited line 1\nEdited line 2")

		// Save changes
		composeRule.onNodeWithText("Save").performClick()

		// Verify new content is shown
		composeRule.onNodeWithText("Edited line 1").assertExists()
		composeRule.onNodeWithText("Edited line 2").assertExists()

		// Also ensure the underlying storage was updated
		val stored = AIMemoryManager.getPermanentMemory(composeRule.activity)
		assert(stored == "Edited line 1\nEdited line 2")
	}
}


