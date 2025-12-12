@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import android.content.SharedPreferences
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class CategoryAllowanceTracker(
    private val prefs: SharedPreferences,
    private val timeProvider: TimeProvider = SystemTimeProvider
) {
    private val expiryByApp = mutableMapOf<String, Instant>()
    private var currentDay: String = dayKey(timeProvider.now())

    fun resetForTests() {
        prefs.edit().clear().commit()
        expiryByApp.clear()
        currentDay = dayKey(timeProvider.now())
    }

    fun startFreeWindowIfEligible(
        packageName: String,
        category: ResolvedCategory,
        now: Instant = timeProvider.now()
    ): Duration? {
        syncDay(now)
        if (category.freeMinutesPerPeriod <= 0 || category.freePeriodsPerDay <= 0) return null

        val used = prefs.getInt(usesKey(packageName), 0)
        if (used >= category.freePeriodsPerDay) return null

        val expiry = now + category.freeMinutesPerPeriod.minutes
        expiryByApp[packageName] = expiry
        prefs.edit().putInt(usesKey(packageName), used + 1).apply()
        return category.freeMinutesPerPeriod.minutes
    }

    fun isInFreeWindow(packageName: String, now: Instant = timeProvider.now()): Boolean {
        syncDay(now)
        val expiry = expiryByApp[packageName] ?: return false
        if (now < expiry) return true
        expiryByApp.remove(packageName)
        return false
    }

    fun remainingFreeTime(packageName: String, now: Instant = timeProvider.now()): Duration? {
        syncDay(now)
        val expiry = expiryByApp[packageName] ?: return null
        val remaining = expiry - now
        return if (remaining.isPositive()) remaining else null
    }

    private fun syncDay(now: Instant) {
        val day = dayKey(now)
        if (day != currentDay) {
            currentDay = day
            expiryByApp.clear()
            prefs.edit().clear().apply()
        }
    }

    private fun usesKey(packageName: String): String = "uses_${currentDay}_$packageName"

    private fun dayKey(now: Instant): String {
        val javaInstant = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
        return javaInstant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}

