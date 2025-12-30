package com.timelinter.app

/**
 * Represents the currently observed foreground application with the resolved category.
 * This is used by the monitoring service and detection smoothing to avoid relying on
 * generic app models that lack category context.
 */
data class ForegroundAppInfo(
    val packageName: String,
    val readableName: String,
    val category: ResolvedCategory
)









