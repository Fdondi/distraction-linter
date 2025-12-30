package com.timelinter.app

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogPersistenceTest {

    private lateinit var root: File
    private lateinit var persistence: EventLogPersistence

    @Before
    fun setup() {
        root = createTempDir(prefix = "eventlogs")
        persistence = EventLogPersistence(root, retentionDays = null)
    }

    @After
    fun teardown() {
        root.deleteRecursively()
    }

    @Test
    fun savesAndLoadsPerDay() {
        val day1 = LocalDate.of(2024, 1, 10)
        val day2 = LocalDate.of(2024, 1, 11)

        val entryDay1 = EventLogEntry(
            id = 1,
            timestamp = dayToMillis(day1) + 1_000,
            type = EventType.SYSTEM,
            title = "Day1",
            details = "first"
        )
        val entryDay2 = EventLogEntry(
            id = 2,
            timestamp = dayToMillis(day2) + 2_000,
            type = EventType.MESSAGE,
            title = "Day2",
            details = "second"
        )

        persistence.append(entryDay1)
        persistence.append(entryDay2)

        val days = persistence.listDays()
        assertEquals(listOf(day2, day1), days)

        val loadedDay1 = persistence.load(day1)
        assertEquals(1, loadedDay1.size)
        assertEquals("Day1", loadedDay1.first().title)

        val loadedDay2 = persistence.load(day2)
        assertEquals(1, loadedDay2.size)
        assertEquals("Day2", loadedDay2.first().title)
    }

    @Test
    fun prunesOlderThanRetention() {
        val today = LocalDate.of(2024, 2, 15)
        persistence.setRetentionDays(2)

        val entryOld = EventLogEntry(
            id = 1,
            timestamp = dayToMillis(today.minusDays(5)),
            type = EventType.SYSTEM,
            title = "Old",
            details = null
        )
        val entryKeep = EventLogEntry(
            id = 2,
            timestamp = dayToMillis(today),
            type = EventType.SYSTEM,
            title = "New",
            details = null
        )

        persistence.append(entryOld)
        persistence.append(entryKeep)
        persistence.pruneIfNeeded(currentDate = today)

        val days = persistence.listDays()
        assertEquals(listOf(today), days)
        val kept = persistence.load(today)
        assertEquals(1, kept.size)
        assertEquals("New", kept.first().title)
        assertTrue(File(root, "${today.minusDays(5)}.json").exists().not())
    }

    private fun dayToMillis(day: LocalDate): Long {
        return ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
}









