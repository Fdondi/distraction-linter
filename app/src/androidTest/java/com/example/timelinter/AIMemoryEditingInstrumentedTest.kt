package com.example.timelinter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class AIMemoryEditingInstrumentedTest {

	private lateinit var appContext: android.content.Context
	private lateinit var fakeTime: FakeTimeProvider

	@Before
	fun setUp() {
		appContext = InstrumentationRegistry.getInstrumentation().targetContext
		fakeTime = FakeTimeProvider(1_000L)
		AIMemoryManager.clearAllMemories(appContext)
	}

	@After
	fun tearDown() {
		AIMemoryManager.clearAllMemories(appContext)
	}

	@Test
	fun setPermanentMemory_replacesExistingContent() {
		// Given existing permanent memory
		AIMemoryManager.addPermanentMemory(appContext, "Line A")
		AIMemoryManager.addPermanentMemory(appContext, "Line B")
		val before = AIMemoryManager.getAllMemories(appContext, fakeTime)
		assertEquals("Line A\nLine B", before)

		// When replacing with edited content
		val edited = "Edited line 1\nEdited line 2"
		AIMemoryManager.setPermanentMemory(appContext, edited)

		// Then only edited content remains as permanent
		val permanent = AIMemoryManager.getPermanentMemory(appContext)
		assertEquals(edited, permanent)
		val all = AIMemoryManager.getAllMemories(appContext, fakeTime)
		assertEquals(edited, all)
	}

	@Test
	fun setPermanentMemory_keepsTemporaryMemoriesIntact() {
		// Given a temporary memory active
        AIMemoryManager.addTemporaryMemory(appContext, "Temp 1", 10.minutes, fakeTime)
		val tempOnly = AIMemoryManager.getAllMemories(appContext, fakeTime)
		assertTrue(tempOnly.contains("Temp 1"))

		// When setting permanent memory
		AIMemoryManager.setPermanentMemory(appContext, "Perm X")

		// Then all memories include both permanent and still-active temp
		val all = AIMemoryManager.getAllMemories(appContext, fakeTime)
		assertTrue(all.contains("Perm X"))
		assertTrue(all.contains("Temp 1"))
	}
}


