package com.timelinter.app

import android.app.NotificationManager

internal object HeadsUpInfoDecider {

    private const val MIN_HEADS_UP_IMPORTANCE = NotificationManager.IMPORTANCE_HIGH

    fun shouldShowHeadsUpPrompt(
        headsUpInfoAlreadyShown: Boolean,
        channelImportance: Int?
    ): Boolean {
        if (headsUpInfoAlreadyShown) return false

        val importance = channelImportance ?: return true
        return importance < MIN_HEADS_UP_IMPORTANCE
    }
}

















