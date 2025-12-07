package com.timelinter.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

class AIMemoryRulesAndTempUITest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivity>()

	@Before
	fun setUp() {
		val context = composeRule.activity
		AIMemoryManager.clearAllMemories(context)
		AIMemoryManager.setPermanentMemory(context, "Permanent baseline")
		AIMemoryManager.setMemoryRules(context, "Initial rules text")
		// Add temp memories that expire on +1 day and +2 days
        AIMemoryManager.addTemporaryMemory(context, "Temp A1", (24 * 60).minutes)
        AIMemoryManager.addTemporaryMemory(context, "Temp A2", (24 * 60).minutes)
        AIMemoryManager.addTemporaryMemory(context, "Temp B1", (48 * 60).minutes)
	}

	@After
	fun tearDown() {
		AIMemoryManager.clearAllMemories(composeRule.activity)
	}

	@Test
	fun rulesAreEditable_andTemporaryGroupsAreShown() {
		// Open AI Log
		composeRule.onNodeWithText("AI Log").performClick()

		// Verify rules field shows initial text
		composeRule.onNodeWithTag("aiMemoryRules").assertExists()
		composeRule.onNodeWithText("Initial rules text").assertExists()

		// Edit rules and save
		composeRule.onNodeWithTag("aiMemoryRules").performTextClearance()
		composeRule.onNodeWithTag("aiMemoryRules").performTextInput("Updated rules")
		composeRule.onNodeWithText("Save Rules").performClick()
		// Confirm persisted
		val stored = AIMemoryManager.getMemoryRules(composeRule.activity)
		assert(stored == "Updated rules")

		// Verify two temporary group sections exist: +1 day and +2 days
		val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
		val group1 = "tempMemoryGroup-" + LocalDate.now().plusDays(1).format(formatter)
		val group2 = "tempMemoryGroup-" + LocalDate.now().plusDays(2).format(formatter)
		composeRule.onNodeWithTag(group1).assertExists()
		composeRule.onNodeWithTag(group2).assertExists()

		// Verify content items present
		composeRule.onNodeWithText("Temp A1").assertExists()
		composeRule.onNodeWithText("Temp A2").assertExists()
		composeRule.onNodeWithText("Temp B1").assertExists()
	}
}


