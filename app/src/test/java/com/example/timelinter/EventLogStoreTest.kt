package com.example.timelinter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogStoreTest {

    @Before
    fun setup() {
        EventLogStore.clear()
    }

    @After
    fun teardown() {
        EventLogStore.clear()
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
        EventLogStore.logTool(ToolCommand.Allow(5, app = "YouTube"))

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
}


