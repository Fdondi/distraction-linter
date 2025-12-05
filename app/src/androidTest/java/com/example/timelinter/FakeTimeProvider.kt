@file:OptIn(ExperimentalTime::class)

package com.example.timelinter

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Deterministic fake time provider for tests. Uses kotlin.time.Instant to match
 * the production TimeProvider interface.
 */
class FakeTimeProvider(initialNowMs: Long = 0L) : TimeProvider {
    private var currentTime: Instant = Instant.fromEpochMilliseconds(initialNowMs)

    override fun now(): Instant = currentTime

    fun advanceMs(deltaMs: Long) {
        advance(deltaMs.milliseconds)
    }

    fun advanceMinutes(minutes: Long) {
        advance(minutes.minutes)
    }

    fun advance(duration: Duration) {
        currentTime += duration
    }
}
