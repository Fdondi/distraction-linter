package com.example.timelinter

import android.content.Intent
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Screen dedicated to managing the list of "good" applications that provide time rewards.
 * Similar to AppSelectionScreen but for beneficial apps.
 */
@Composable
fun GoodAppSelectionScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // --- State holders ---
    var searchQuery by remember { mutableStateOf("") }
    var selectedApps by remember { mutableStateOf(GoodAppManager.getSelectedApps(context)) }

    // Selected app info is fetched immediately (fast, because we only look up labels for the handful of selected apps)
    var selectedAppInfos by remember { mutableStateOf(listOf<AppInfo>()) }

    // All apps might take a while to load – load them in the background
    var allApps by remember { mutableStateOf(listOf<AppInfo>()) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Helper to determine if we have QUERY_ALL_PACKAGES permission/access (Android 11+)
    fun canQueryPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null)
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = context.packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                resolveInfos.isNotEmpty()
            } catch (e: Exception) {
                Log.e("GoodAppSelectionScreen", "Error checking package visibility", e)
                false
            }
        } else {
            true
        }
    }

    // Immediately resolve labels for selected apps
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val infos = selectedApps.mapNotNull { pkg ->
            try {
                val appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                AppInfo(pkg, appName, isSelected = true)
            } catch (e: Exception) {
                // App might have been uninstalled – skip it
                null
            }
        }.sortedBy { it.appName }
        selectedAppInfos = infos
    }

    // Load the complete app list asynchronously
    LaunchedEffect(Unit) {
        if (!canQueryPackages()) {
            showPermissionDialog = true
            return@LaunchedEffect
        }

        val pm = context.packageManager
        val installedApps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL).map { ri ->
                    val label = ri.loadLabel(pm).toString()
                    AppInfo(
                        packageName = ri.activityInfo.packageName,
                        appName = label,
                        isSelected = false
                    )
                }
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA).map { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    AppInfo(
                        packageName = app.packageName,
                        appName = label,
                        isSelected = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GoodAppSelectionScreen", "Error loading apps", e)
            emptyList()
        }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.appName }

        allApps = installedApps
    }

    // Utility for a simple fuzzy search (space-insensitive, case-insensitive)
    fun String.matchesSearch(query: String): Boolean {
        if (query.isEmpty()) return true
        val normalizedThis = lowercase().replace(" ", "")
        val normalizedQuery = query.lowercase().replace(" ", "")
        return normalizedThis.contains(normalizedQuery)
    }

    Scaffold(
        topBar = {
            com.example.timelinter.ui.components.AppTopBar(
                title = "Select Good Apps",
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
            // --- Permission dialog ---
            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionDialog = false },
                    title = { Text("Permission Required") },
                    text = {
                        Text("Time Linter needs permission to see your installed apps. Please grant this permission in your system settings to continue.")
                    },
                    confirmButton = {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                            showPermissionDialog = false
                        }) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        Button(onClick = onNavigateBack) { Text("Cancel") }
                    }
                )
            }

            // Info card explaining good apps
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Good Apps", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Select apps that are beneficial to use. Time spent in these apps will reward you with bonus time that extends beyond your normal limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // --- Search bar ---
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true
            )

            // --- Selected apps ---
            if (selectedAppInfos.isNotEmpty()) {
                Text(
                    "Selected Good Apps",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(selectedAppInfos) { app ->
                        GoodAppListItem(
                            app = app,
                            isSelected = true,
                            onToggleSelection = { isSelected ->
                                selectedApps = if (isSelected) {
                                    selectedApps + app.packageName
                                } else {
                                    selectedApps - app.packageName
                                }
                                GoodAppManager.saveSelectedApps(context, selectedApps)
                                // Update local selected info list
                                selectedAppInfos = selectedAppInfos.filterNot { it.packageName == app.packageName }
                            }
                        )
                    }
                }
            }

            // --- All apps ---
            Text(
                "All Apps",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(
                    allApps.filter {
                        it.packageName !in selectedApps && (searchQuery.isEmpty() || it.appName.matchesSearch(searchQuery))
                    }
                ) { app ->
                    GoodAppListItem(
                        app = app,
                        isSelected = false,
                        onToggleSelection = { isSelected ->
                            selectedApps = if (isSelected) {
                                selectedApps + app.packageName
                            } else {
                                selectedApps - app.packageName
                            }
                            GoodAppManager.saveSelectedApps(context, selectedApps)
                            // If app selected, we add it to selected list immediately for responsiveness
                            if (isSelected) {
                                selectedAppInfos = (selectedAppInfos + app.copy(isSelected = true)).sortedBy { it.appName }
                            }
                        }
                    )
                }
            }
        }
    }
}

// Reusable list item composable (local to this file)
@Composable
private fun GoodAppListItem(
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



