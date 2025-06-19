package com.example.timelinter

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedApps by remember { mutableStateOf(setOf<String>()) }
    var allApps by remember { mutableStateOf(listOf<AppInfo>()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    
    // Timer settings
    var observeTimerMinutes by remember { mutableStateOf(SettingsManager.getObserveTimerMinutes(context)) }
    var responseTimerMinutes by remember { mutableStateOf(SettingsManager.getResponseTimerMinutes(context)) }
    
    // Function to check if we can query packages
    fun canQueryPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null)
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = context.packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                resolveInfos.isNotEmpty()
            } catch (e: Exception) {
                Log.e("SettingsScreen", "Error checking package visibility", e)
                false
            }
        } else {
            true // Pre-Android 11, we can always query
        }
    }

    // Load apps and selected apps on first composition
    LaunchedEffect(Unit) {
        if (!canQueryPackages()) {
            showPermissionDialog = true
            return@LaunchedEffect
        }

        val packageManager = context.packageManager
        val installedApps = try {
            // For Android 11+ (API 30+), we need to use queryIntentActivities
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null)
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                    .map { resolveInfo ->
                        val appName = resolveInfo.loadLabel(packageManager).toString()
                        val packageName = resolveInfo.activityInfo.packageName
                        Log.d("SettingsScreen", "Found app: $appName ($packageName)")
                        AppInfo(
                            packageName = packageName,
                            appName = appName,
                            isSelected = false
                        )
                    }
            } else {
                // For older versions, we can use getInstalledApplications
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { app ->
                        val appName = packageManager.getApplicationLabel(app).toString()
                        Log.d("SettingsScreen", "Found app: $appName (${app.packageName})")
                        AppInfo(
                            packageName = app.packageName,
                            appName = appName,
                            isSelected = false
                        )
                    }
            }
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Error loading apps", e)
            emptyList()
        }.filter { app ->
            // Only filter out our own app
            app.packageName != context.packageName
        }.sortedBy { it.appName }
        
        Log.d("SettingsScreen", "Found ${installedApps.size} installed apps")
        allApps = installedApps
        
        // Load saved selections or use defaults
        val savedSelections = TimeWasterAppManager.getSelectedApps(context)
        if (savedSelections.isEmpty()) {
            // Pre-select common time-wasting apps if no saved selections
            val commonTimeWasters = setOf(
                "com.twitter.android", // X (Twitter)
                "com.facebook.katana", // Facebook
                "com.google.android.youtube", // YouTube
                "com.netflix.mediaclient", // Netflix
                "com.reddit.frontpage", // Reddit
                "com.instagram.android", // Instagram
                "com.tiktok.android", // TikTok
                "com.snapchat.android", // Snapchat
                "com.pinterest", // Pinterest
                "com.linkedin.android" // LinkedIn
            )
            selectedApps = commonTimeWasters
            TimeWasterAppManager.saveSelectedApps(context, commonTimeWasters)
            Log.d("SettingsScreen", "Using default time-wasting apps")
        } else {
            selectedApps = savedSelections
            Log.d("SettingsScreen", "Loaded ${savedSelections.size} saved selections")
        }
    }

    // Helper function for flexible search
    fun String.matchesSearch(query: String): Boolean {
        if (query.isEmpty()) return true
        val normalizedThis = this.lowercase().replace(" ", "")
        val normalizedQuery = query.lowercase().replace(" ", "")
        return normalizedThis.contains(normalizedQuery)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Apps") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Timers") }
                )
            }

            when (selectedTab) {
                0 -> {
                    // Apps Tab Content
                    if (showPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { showPermissionDialog = false },
                            title = { Text("Permission Required") },
                            text = { 
                                Text(
                                    "Time Linter needs permission to see your installed apps. " +
                                    "Please grant this permission in your system settings to continue."
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        // Open app settings
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                        showPermissionDialog = false
                                    }
                                ) {
                                    Text("Open Settings")
                                }
                            },
                            dismissButton = {
                                Button(onClick = onNavigateBack) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    // Search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        placeholder = { Text("Search apps...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        singleLine = true
                    )

                    // Selected apps section
                    if (selectedApps.isNotEmpty()) {
                        Text(
                            "Selected Apps",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f)
                        ) {
                            items(
                                allApps.filter { it.packageName in selectedApps }
                                    .sortedBy { it.appName }
                            ) { app ->
                                AppListItem(
                                    app = app,
                                    isSelected = true,
                                    onToggleSelection = { isSelected ->
                                        selectedApps = if (isSelected) {
                                            selectedApps + app.packageName
                                        } else {
                                            selectedApps - app.packageName
                                        }
                                        TimeWasterAppManager.saveSelectedApps(context, selectedApps)
                                    }
                                )
                            }
                        }
                    }

                    // All apps section
                    Text(
                        "All Apps",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            allApps.filter { 
                                it.packageName !in selectedApps &&
                                (searchQuery.isEmpty() || 
                                 it.appName.matchesSearch(searchQuery))
                            }
                        ) { app ->
                            AppListItem(
                                app = app,
                                isSelected = false,
                                onToggleSelection = { isSelected ->
                                    selectedApps = if (isSelected) {
                                        selectedApps + app.packageName
                                    } else {
                                        selectedApps - app.packageName
                                    }
                                    TimeWasterAppManager.saveSelectedApps(context, selectedApps)
                                }
                            )
                        }
                    }
                }
                1 -> {
                    // Timers Tab Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Observe Timer Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Observe Timer",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "How long to wait before checking if you're wasting time again",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("${observeTimerMinutes} minutes")
                                    Slider(
                                        value = observeTimerMinutes.toFloat(),
                                        onValueChange = { 
                                            observeTimerMinutes = it.toInt()
                                            SettingsManager.setObserveTimerMinutes(context, observeTimerMinutes)
                                            // Also update replenish interval to match observe timer
                                            SettingsManager.setReplenishIntervalMinutes(context, observeTimerMinutes)
                                        },
                                        valueRange = 1f..30f,
                                        steps = 28,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Response Timer Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Response Timer",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "How long to wait for your response before considering it ignored",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("${responseTimerMinutes} minute${if (responseTimerMinutes > 1) "s" else ""}")
                                    Slider(
                                        value = responseTimerMinutes.toFloat(),
                                        onValueChange = { 
                                            responseTimerMinutes = it.toInt()
                                            SettingsManager.setResponseTimerMinutes(context, responseTimerMinutes)
                                        },
                                        valueRange = 1f..10f,
                                        steps = 8,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Max Threshold Minutes Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                var maxThresholdMinutes by remember { mutableStateOf(SettingsManager.getMaxThresholdMinutes(context)) }
                                Text(
                                    text = "Max Allowed Minutes",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "How much time you can spend in wasteful apps before intervention (bucket size)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("${maxThresholdMinutes} min")
                                    Slider(
                                        value = maxThresholdMinutes.toFloat(),
                                        onValueChange = {
                                            maxThresholdMinutes = it.toInt()
                                            SettingsManager.setMaxThresholdMinutes(context, maxThresholdMinutes)
                                        },
                                        valueRange = 1f..60f,
                                        steps = 59,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Replenish Amount Minutes Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                var replenishAmountMinutes by remember { mutableStateOf(SettingsManager.getReplenishAmountMinutes(context)) }
                                Text(
                                    text = "Replenish Amount",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "How many minutes are restored to your allowance each interval you stay off wasteful apps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("${replenishAmountMinutes} min")
                                    Slider(
                                        value = replenishAmountMinutes.toFloat(),
                                        onValueChange = {
                                            replenishAmountMinutes = it.toInt()
                                            SettingsManager.setReplenishAmountMinutes(context, replenishAmountMinutes)
                                        },
                                        valueRange = 1f..10f,
                                        steps = 9,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Information Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "How Timers Work",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "• Observe Timer: After you stop using a time-wasting app, Time Linter waits this long before checking again (also used as the replenish interval)\n" +
                                          "• Response Timer: When Time Linter sends you a message, it waits this long for your reply before sending a follow-up\n" +
                                          "• Max Allowed Minutes: The total time you can spend in wasteful apps before intervention (bucket size)\n" +
                                          "• Replenish Amount: How much time is restored to your allowance each interval you stay off wasteful apps (interval = Observe Timer)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onToggleSelection
        )
        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSelected: Boolean
) 