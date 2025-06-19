package com.example.timelinter

// Data model representing an application entry used across multiple settings screens.
// Having it in its own file avoids duplicate declarations and makes it reusable.
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSelected: Boolean = false
) 