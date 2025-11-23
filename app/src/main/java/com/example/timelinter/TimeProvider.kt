package com.example.timelinter

interface TimeProvider {
    fun now(): kotlin.time.Instant
}

object SystemTimeProvider : TimeProvider {
    override fun now(): kotlin.time.Instant = kotlin.time.Clock.System.now()
}



