@file:OptIn(ExperimentalTime::class)

package com.example.timelinter

import kotlin.time.ExperimentalTime

interface TimeProvider {
    fun now(): kotlin.time.Instant
}

object SystemTimeProvider : TimeProvider {
    override fun now(): kotlin.time.Instant = kotlin.time.Clock.System.now()
}



