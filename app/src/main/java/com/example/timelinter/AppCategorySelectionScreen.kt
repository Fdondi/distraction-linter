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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Generic screen for managing app categories (good apps, bad apps, etc.)
 */
@OptIn(ExperimentalMaterial3Api::class) // Add this line
@Composable
fun AppCategorySelectionScreen(
    title: String,
    selectedSectionLabel: String,
    explanationLabel: String,
    explanationPlaceholder: String,
    manager: AppCategoryManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedApps by remember { mutableStateOf(manager.getSelectedApps(context)) }
    var selectedAppInfos by remember { mutableStateOf(listOf<AppInfo>()) }
    var allApps by remember { mutableStateOf(listOf<AppInfo>()) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun canQueryPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null)
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                val list = context.packageManager.queryIntentActivities(mainIntent, 0)
                list.size > 100
            } catch (e: SecurityException) {
                false
            }
        } else {
            true
        }
    }

    fun String.matchesSearch(query: String): Boolean {
        if (query.isEmpty()) return true
        val normalizedThis = this.lowercase().replace(" ", "")
        val normalizedQuery = query.lowercase().replace(" ", "")
        return normalizedThis.contains(normalizedQuery)
    }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        selectedAppInfos = selectedApps.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                AppInfo(
                    packageName = pkg,
                    appName = label,
                    isSelected = true
                )
            } catch (e: Exception) {
                Log.w("AppCategorySelection", "App $pkg not found", e)
                null
            }
        }.sortedBy { it.appName }
    }

    LaunchedEffect(Unit) {
        if (!canQueryPackages()) {
            showPermissionDialog = true
            return@LaunchedEffect
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val installedApps = try {
            pm.queryIntentActivities(mainIntent, 0).mapNotNull { resolveInfo ->
                try {
                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        isSelected = false
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("AppCategorySelection", "Error loading apps", e)
            emptyList()
        }.filter { app ->
            app.packageName != context.packageName
        }.sortedBy { it.appName }
        
        allApps = installedApps
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required") },
            text = { Text("To see all installed apps, you need to grant the QUERY_ALL_PACKAGES permission in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, "Search") },
                singleLine = true
            )

            if (selectedAppInfos.isNotEmpty()) {
                Text(
                    selectedSectionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(selectedAppInfos) { app ->
                        AppCategoryListItem(
                            app = app,
                            isSelected = true,
                            explanationLabel = explanationLabel,
                            explanationPlaceholder = explanationPlaceholder,
                            manager = manager,
                            onToggleSelection = { isSelected ->
                                selectedApps = if (isSelected) {
                                    selectedApps + app.packageName
                                } else {
                                    selectedApps - app.packageName
                                }
                                manager.saveSelectedApps(context, selectedApps)
                                selectedAppInfos = selectedAppInfos.filterNot { it.packageName == app.packageName }
                            }
                        )
                    }
                }
            }

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
                    AppCategoryListItem(
                        app = app,
                        isSelected = false,
                        explanationLabel = explanationLabel,
                        explanationPlaceholder = explanationPlaceholder,
                        manager = manager,
                        onToggleSelection = { isSelected ->
                            selectedApps = if (isSelected) {
                                selectedApps + app.packageName
                            } else {
                                selectedApps - app.packageName
                            }
                            manager.saveSelectedApps(context, selectedApps)
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

@Composable
private fun AppCategoryListItem(
    app: AppInfo,
    isSelected: Boolean,
    explanationLabel: String,
    explanationPlaceholder: String,
    manager: AppCategoryManager,
    onToggleSelection: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var explanation by remember { mutableStateOf(manager.getExplanation(context, app.packageName)) }
    var isEditing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onToggleSelection
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Show first line of explanation in gray if it exists and not editing
                if (isSelected && !isEditing && explanation.isNotEmpty()) {
                    Text(
                        text = explanation.lines().firstOrNull() ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (isSelected) {
                IconButton(onClick = { isEditing = !isEditing }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = if (isEditing) "Close editor" else "Edit explanation",
                        tint = if (explanation.isNotEmpty()) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        if (isSelected && isEditing) {
            OutlinedTextField(
                value = explanation,
                onValueChange = { newValue ->
                    explanation = newValue
                    manager.saveExplanation(context, app.packageName, newValue)
                },
                label = { Text(explanationLabel) },
                placeholder = { Text(explanationPlaceholder) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

// Convenience wrappers for backward compatibility
@Composable
fun AppSelectionScreen(onNavigateBack: () -> Unit) {
    AppCategorySelectionScreen(
        title = "Time-Wasting Apps",
        selectedSectionLabel = "Selected Time-Wasting Apps",
        explanationLabel = "Why is this a bad app?",
        explanationPlaceholder = "e.g., Too distracting, wastes time, addiction...",
        manager = AppCategoryManager("time_waster_apps", "TimeWasterAppManager"),
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun GoodAppSelectionScreen(onNavigateBack: () -> Unit) {
    AppCategorySelectionScreen(
        title = "Good Apps",
        selectedSectionLabel = "Selected Good Apps",
        explanationLabel = "Why is this a good app?",
        explanationPlaceholder = "e.g., Helps with productivity, learning, exercise...",
        manager = AppCategoryManager("good_apps", "GoodAppManager"),
        onNavigateBack = onNavigateBack
    )
}

