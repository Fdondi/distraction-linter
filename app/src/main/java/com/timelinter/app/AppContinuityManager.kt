@file:OptIn(ExperimentalTime::class)

package com.timelinter.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Keeps the perceived foreground app stable through short detection lapses.
 *
 * Rules:
 * - If detection briefly reports "no app", keep the previous app for the configured gap window.
 * - If the current app is in a free bucket, ignore other app detections until the resume window
 *   elapses. This prevents temporary switches from consuming new free allowances.
 */
class AppContinuityManager(
    private val timeProvider: TimeProvider,
    private val noAppGapDurationProvider: () -> Duration,
    private val freeBucketResumeDurationProvider: () -> Duration
) {

    private var pendingTarget: ForegroundAppInfo? = null
    private var pendingStartedAt: Instant? = null
    private var pendingHoldDuration: Duration = ZERO

    fun resolve(
        currentApp: ForegroundAppInfo?,
        detectedApp: ForegroundAppInfo?,
        currentAppIsFree: Boolean
    ): ForegroundAppInfo? {
        val now = timeProvider.now()

        if (samePackage(currentApp, detectedApp)) {
            clearPending()
            return detectedApp
        }

        val holdDuration = determineHoldDuration(currentApp, detectedApp, currentAppIsFree)
        if (holdDuration.isPositive()) {
            updatePending(detectedApp, holdDuration, now)

            val elapsed = pendingStartedAt?.let { now - it } ?: ZERO
            if (currentApp != null && elapsed < pendingHoldDuration) {
                return currentApp
            }

            clearPending()
            return detectedApp
        }

        clearPending()
        return detectedApp
    }

    @Suppress("SameParameterValue")
    private fun determineHoldDuration(
        currentApp: ForegroundAppInfo?,
        detectedApp: ForegroundAppInfo?,
        currentAppIsFree: Boolean
    ): Duration {
        if (currentApp == null) return ZERO
        return when {
            detectedApp == null -> noAppGapDurationProvider()
            currentAppIsFree -> freeBucketResumeDurationProvider()
            else -> ZERO
        }
    }

    private fun updatePending(target: ForegroundAppInfo?, duration: Duration, now: Instant) {
        if (!samePackage(pendingTarget, target)) {
            pendingTarget = target
            pendingStartedAt = now
            pendingHoldDuration = duration
            return
        }

        if (pendingHoldDuration != duration) {
            pendingHoldDuration = duration
            pendingStartedAt = now
        }
    }

    private fun clearPending() {
        pendingTarget = null
        pendingStartedAt = null
        pendingHoldDuration = ZERO
    }

    private fun samePackage(a: ForegroundAppInfo?, b: ForegroundAppInfo?): Boolean {
        val aPkg = a?.packageName
        val bPkg = b?.packageName
        if (aPkg == null || bPkg == null) return false
        return aPkg == bPkg
    }
}

