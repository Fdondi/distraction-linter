package com.example.timelinter

class FakeTimeProvider(initialNowMs: Long = 0L) : TimeProvider {
    private var currentTimeMs: Long = initialNowMs

    override fun now(): Long = currentTimeMs

    fun advanceMs(deltaMs: Long) {
        currentTimeMs += deltaMs
    }

    fun advanceMinutes(minutes: Long) {
        advanceMs(minutes * 60_000L)
    }
}



