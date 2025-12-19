package com.timelinter.app

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogStoreContextTest {

    @Before
    fun setUp() {
        EventLogStore.clear()
        EventLogStore.setContextLineProvider { "Bucket=10m free=false" }
    }

    @After
    fun tearDown() {
        EventLogStore.clear()
        EventLogStore.setContextLineProvider(null)
    }

    @Test
    fun appendsContextLineToDetails() {
        EventLogStore.logSystem("hello")

        val entry = EventLogStore.events.value.first()

        val details = entry.details ?: ""
        assertTrue(details.contains("hello"))
        assertTrue(details.trimEnd().endsWith("Bucket=10m free=false"))
    }
}







