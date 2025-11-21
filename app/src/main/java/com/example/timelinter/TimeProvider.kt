package com.example.timelinter

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

interface TimeProvider {
    fun now(): Instant
}

object SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Clock.System.now()
}



