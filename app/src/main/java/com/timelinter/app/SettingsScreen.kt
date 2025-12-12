package com.timelinter.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val responseMin = 10.seconds
    val responseMax = 10.minutes
    var responseSlider by remember {
        mutableStateOf<Float>(
            durationToSlider(SettingsManager.getResponseTimer(context), responseMin, responseMax)
        )
    }
    val responseInterval = sliderToDuration(responseSlider, responseMin, responseMax)
    val wakeupMin = 10.seconds
    val wakeupMax = 10.minutes
    var wakeupSlider by remember {
        mutableStateOf<Float>(
            durationToSlider(SettingsManager.getWakeupInterval(context), wakeupMin, wakeupMax)
        )
    }
    val wakeupInterval: Duration = sliderToDuration(wakeupSlider, wakeupMin, wakeupMax)

    // Function to check if we can query packages
    // On Android 11+, package visibility is controlled by <queries> in manifest, not runtime permissions
    fun canQueryPackages(): Boolean {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = context.packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            // If we can query at all, we're good - the <queries> declaration in manifest handles visibility
            true // Just try to query, don't block on count
        } catch (e: SecurityException) {
            Log.e("SettingsScreen", "Security exception checking package visibility", e)
            false
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Error checking package visibility", e)
            // On error, assume we can't query (but this shouldn't happen with proper <queries> declaration)
            false
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
        selectedApps = savedSelections
        Log.d("SettingsScreen", "Loaded ${savedSelections.size} saved selections")
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
            com.timelinter.app.ui.components.AppTopBar(
                title = "Settings",
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
            PrimaryTabRow(selectedTabIndex = selectedTab) {
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
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("AI") }
                )
            }

            when (selectedTab) {
                0 -> {
                    // Apps Tab Content
                    if (showPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { showPermissionDialog = false },
                            title = { Text("Cannot Access Apps") },
                            text = {
                                Text(
                                    "Time Linter cannot see your installed apps. " +
                                            "This may be due to Android's package visibility restrictions. " +
                                            "The app should work with the default settings, but if you're having issues, " +
                                            "please check that the app has proper permissions in system settings."
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
                                Button(onClick = { showPermissionDialog = false }) {
                                    Text("OK")
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
                        // Wakeup Interval Setting (log scale 10s-10m)
                        LogDurationCard(
                            title = "Wakeup Interval",
                            description = "How often the service wakes to check the current app (log scaled)",
                            value = wakeupInterval,
                            min = wakeupMin,
                            max = wakeupMax
                        ) { interval ->
                            wakeupSlider = durationToSlider(interval, wakeupMin, wakeupMax)
                            SettingsManager.setWakeupInterval(context, interval)
                        }

                        // Response Timer Setting (log scale 10s-10m)
                        LogDurationCard(
                            title = "Response Timer",
                            description = "How long to wait for your response before considering it ignored",
                            value = responseInterval,
                            min = responseMin,
                            max = responseMax
                        ) { duration ->
                            responseSlider = durationToSlider(duration, responseMin, responseMax)
                            SettingsManager.setResponseTimer(context, duration)
                        }

                        // Max Threshold Minutes Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                var maxThreshold by remember { mutableStateOf(SettingsManager.getMaxThreshold(context)) }
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
                                    Text("${maxThreshold.inWholeMinutes} min")
                                    Slider(
                                        value = maxThreshold.inWholeMinutes.toFloat(),
                                        onValueChange = {
                                            maxThreshold = it.roundToInt().minutes
                                            SettingsManager.setMaxThreshold(context, maxThreshold)
                                        },
                                        valueRange = 1f..60f,
                                        steps = 59,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Replenish Rate Setting (dimensionless fraction of an hour)
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                var replenishRateFraction by remember {
                                    mutableFloatStateOf(SettingsManager.getReplenishRateFraction(context))
                                }
                                val displayMinutesPerHour = (replenishRateFraction * 60f).roundToInt()
                                Text(
                                    text = "Replenish Rate",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Fraction of an hour restored when off wasteful apps (e.g., 0.1 = 6 min/hour)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(String.format("%.2f (≈%d min/hour)", replenishRateFraction, displayMinutesPerHour))
                                    Slider(
                                        value = replenishRateFraction,
                                        onValueChange = { value ->
                                            val clamped = value.coerceIn(0f, 2f) // allow up to 120 min/hour if desired
                                            replenishRateFraction = clamped
                                            SettingsManager.setReplenishRateFraction(context, clamped)
                                        },
                                        valueRange = 0f..2f,
                                        steps = 200,
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
                                    text = "• Wakeup Interval: How often the service wakes up to check the current app (log scaled 10s–10m).\n" +
                                            "• Response Timer: When Time Linter sends you a message, it waits this long for your reply before sending a follow-up.\n" +
                                            "• Max Allowed Minutes: The total time you can spend in wasteful apps before intervention (bucket size).\n" +
                                            "• Replenish Rate: How many minutes are restored per hour you stay off wasteful apps.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // AI Settings
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Force backend mode always; hide direct mode.
                        LaunchedEffect(Unit) {
                            SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
                        }
                        var isSignedIn by remember { mutableStateOf(ApiKeyManager.hasGoogleIdToken(context)) }
                        val coroutineScope = rememberCoroutineScope()

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("AI Access (Subscription)", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Backend mode is always used.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                if (isSignedIn) {
                                    // Collapse the sign-in section once signed in.
                                    Text("✅ Signed in with Google", color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Subscription-backed AI is active.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Text("Not signed in", color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = {
                                        coroutineScope.launch {
                                            val token = AuthManager.signIn(context)
                                            if (token != null) {
                                                isSignedIn = true
                                            }
                                        }
                                    }) {
                                        Text("Sign in with Google")
                                    }
                                }
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
            modifier = Modifier.weight(1f)
        )
    }
}
