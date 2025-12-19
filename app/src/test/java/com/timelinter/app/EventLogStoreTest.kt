package com.timelinter.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

class EventLogStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        EventLogStore.clear(clearPersistence = true)
        tempDir = createTempDir(prefix = "eventlogstore")
        EventLogStore.setNowProviderForTests { System.currentTimeMillis() }
        EventLogStore.configurePersistenceIfNeeded(
            context = TestContext(tempDir),
            retentionDaysOverride = null
        )
    }

    @After
    fun teardown() {
        EventLogStore.clear(clearPersistence = true)
        tempDir.deleteRecursively()
    }

    @Test
    fun events_areReverseChronological_whenLogged() {
        EventLogStore.logSystem("Boot")
        Thread.sleep(2)
        EventLogStore.logMessage("user", "Hello")
        Thread.sleep(2)
        EventLogStore.logMessage("model", "Hi there")

        val events = EventLogStore.events.value
        assertEquals(3, events.size)
        // Most recent on top
        assertTrue(events[0].timestamp >= events[1].timestamp)
        assertTrue(events[1].timestamp >= events[2].timestamp)
        assertEquals(EventType.MESSAGE, events[0].type)
        assertEquals(EventType.MESSAGE, events[1].type)
        assertEquals(EventType.SYSTEM, events[2].type)
    }

    @Test
    fun search_matches_title_details_role_and_type_caseInsensitive() {
        EventLogStore.logSystem("Boot sequence started")
        EventLogStore.logMessage("user", "I feel distracted")
        EventLogStore.logTool(ToolCommand.Allow(5.minutes, app = "YouTube"))

        // by details
        val byApp = EventLogStore.search("youtube")
        assertEquals(1, byApp.size)
        assertEquals(EventType.TOOL, byApp.first().type)

        // by role
        val byRole = EventLogStore.search("USER")
        assertEquals(1, byRole.size)
        assertEquals(EventType.MESSAGE, byRole.first().type)

        // by type name
        val byType = EventLogStore.search("system")
        assertEquals(1, byType.size)
        assertEquals(EventType.SYSTEM, byType.first().type)
    }

    @Test
    fun loadDay_switchesVisibleEvents() {
        val day1 = LocalDate.of(2024, 3, 10)
        val day2 = LocalDate.of(2024, 3, 11)

        EventLogStore.setNowProviderForTests { dayToMillis(day1) }
        EventLogStore.logSystem("Day1")

        EventLogStore.setNowProviderForTests { dayToMillis(day2) }
        EventLogStore.logSystem("Day2")

        // Initially showing current day (day2)
        assertEquals(day2, EventLogStore.currentDay.value)
        assertEquals(1, EventLogStore.events.value.size)
        assertEquals("Day2", EventLogStore.events.value.first().title)

        // Load previous day on demand
        EventLogStore.loadDay(day1)

        val loaded = EventLogStore.events.value
        assertEquals(1, loaded.size)
        assertEquals("Day1", loaded.first().title)
        assertTrue(EventLogStore.availableDays.value.containsAll(listOf(day1, day2)))
    }

    @Test
    fun logToolParseFailureAddsToolErrorEvent() {
        EventLogStore.logToolParseFailure(
            ToolCallIssue(
                reason = ToolCallIssueReason.INVALID_ARGS,
                rawText = "allow(\"ten\")"
            )
        )

        val events = EventLogStore.events.value
        assertEquals(1, events.size)
        val entry = events.first()
        assertEquals(EventType.TOOL_ERROR, entry.type)
        assertTrue(entry.details!!.contains("allow(\"ten\")"))
    }

    @Test
    fun initializesCurrentDayUsingSystemClock() {
        EventLogStore.clear(clearPersistence = true)
        val today = LocalDate.now(ZoneOffset.UTC)
        assertEquals(today, EventLogStore.currentDay.value)
    }

    private fun dayToMillis(day: LocalDate): Long {
        return ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Minimal context wrapper to supply filesDir for persistence in JVM tests.
     */
    private data class TestContext(val dir: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File {
            return File(dir, "files").also { it.mkdirs() }
        }
    }
}


