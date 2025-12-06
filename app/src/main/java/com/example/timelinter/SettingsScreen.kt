package com.example.timelinter

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

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch

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
    var observeTimer by remember { mutableStateOf(SettingsManager.getObserveTimer(context)) }
    var responseTimer by remember { mutableStateOf(SettingsManager.getResponseTimer(context)) }

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
            com.example.timelinter.ui.components.AppTopBar(
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
                                    Text("${observeTimer.inWholeMinutes} minutes")
                                    Slider(
                                        value = observeTimer.inWholeMinutes.toFloat(),
                                        onValueChange = {
                                            observeTimer = it.roundToInt().minutes
                                            SettingsManager.setObserveTimer(context, observeTimer)
                                            // Also update replenish interval to match observe timer
                                            SettingsManager.setReplenishInterval(context, observeTimer)
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
                                    Text("${responseTimer.inWholeMinutes} minute${if (responseTimer.inWholeMinutes > 1) "s" else ""}")
                                    Slider(
                                        value = responseTimer.inWholeMinutes.toFloat(),
                                        onValueChange = {
                                            responseTimer = it.roundToInt().minutes
                                            SettingsManager.setResponseTimer(context, responseTimer)
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

                        // Replenish Amount Minutes Setting
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                var replenishAmount by remember { mutableStateOf(SettingsManager.getReplenishAmount(context)) }
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
                                    Text("${replenishAmount.inWholeMinutes} min")
                                    Slider(
                                        value = replenishAmount.inWholeMinutes.toFloat(),
                                        onValueChange = {
                                            replenishAmount = it.roundToInt().minutes
                                            SettingsManager.setReplenishAmount(context, replenishAmount)
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
                2 -> {
                    // AI Settings
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        var aiMode by remember { mutableStateOf(SettingsManager.getAIMode(context)) }
                        var apiKey by remember { mutableStateOf(ApiKeyManager.getKey(context) ?: "") }
                        var isSignedIn by remember { mutableStateOf(!ApiKeyManager.getGoogleIdToken(context).isNullOrEmpty()) }
                        val coroutineScope = rememberCoroutineScope()

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("AI Mode", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = aiMode == SettingsManager.AI_MODE_DIRECT,
                                        onClick = { 
                                            aiMode = SettingsManager.AI_MODE_DIRECT
                                            SettingsManager.setAIMode(context, aiMode)
                                        }
                                    )
                                    Text("Direct (API Key)")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = aiMode == SettingsManager.AI_MODE_BACKEND,
                                        onClick = { 
                                            aiMode = SettingsManager.AI_MODE_BACKEND
                                            SettingsManager.setAIMode(context, aiMode)
                                        }
                                    )
                                    Text("Subscription (Backend)")
                                }
                            }
                        }

                        if (aiMode == SettingsManager.AI_MODE_DIRECT) {
                             OutlinedTextField(
                                 value = apiKey,
                                 onValueChange = { 
                                     apiKey = it
                                     ApiKeyManager.saveKey(context, it)
                                 },
                                 label = { Text("Gemini API Key") },
                                 modifier = Modifier.fillMaxWidth(),
                                 visualTransformation = PasswordVisualTransformation()
                             )
                             Text(
                                 text = "Get your API key from Google AI Studio",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                        } else {
                             Card(modifier = Modifier.fillMaxWidth()) {
                                 Column(modifier = Modifier.padding(16.dp)) {
                                     Text("Subscription Status", style = MaterialTheme.typography.titleMedium)
                                     Spacer(modifier = Modifier.height(8.dp))
                                     
                                     if (isSignedIn) {
                                         Text("✅ Signed in with Google", color = MaterialTheme.colorScheme.primary)
                                         Spacer(modifier = Modifier.height(8.dp))
                                         Text(
                                             text = "The app will use your subscription to access AI features.",
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