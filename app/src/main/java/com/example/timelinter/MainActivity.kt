package com.example.timelinter

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.util.Linkify
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape // Added for the status box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Added for text color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.example.timelinter.ui.components.ScrollableTextFieldWithScrollbar
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    private var showUsageAccessDialog by mutableStateOf(false)
    private var showNotificationPermissionRationale by mutableStateOf(false)
    private var showHeadsUpInfoDialog by mutableStateOf(false)
    private var isMonitoringActive by mutableStateOf(false)
    private var apiKeyPresent by mutableStateOf(false)
    private var userNotes by mutableStateOf("")
    private var coachName by mutableStateOf("Adam")

    // Launcher for Notification Permission
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Now check for Usage Access.
            checkAndRequestUsageAccess()
        } else {
            // Explain to the user that the feature is unavailable because the
            // feature requires a permission that the user has denied.
            showNotificationPermissionRationale = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check for API Key initially
        apiKeyPresent = ApiKeyManager.hasKey(this)
        // Load user notes
        userNotes = ApiKeyManager.getUserNotes(this)
        // Load coach name
        coachName = ApiKeyManager.getCoachName(this)
        Log.d("MainActivity", "Coach name loaded: $coachName")

        setContent {
            MaterialTheme {
                var showAppsScreen by remember { mutableStateOf(false) }
                var showTimerScreen by remember { mutableStateOf(false) }
                var showLogScreen by remember { mutableStateOf(false) }

                if (showAppsScreen) {
                    AppSelectionScreen(onNavigateBack = { showAppsScreen = false })
                } else if (showTimerScreen) {
                    TimerSettingsScreen(onNavigateBack = { showTimerScreen = false })
                } else if (showLogScreen) {
                    AILogScreen(onNavigateBack = { showLogScreen = false })
                } else {
                    TimeLinterApp(
                        isMonitoring = isMonitoringActive,
                        onToggleMonitoring = { attemptStartMonitoring() },
                        showUsageAccessDialog = showUsageAccessDialog,
                        onDismissUsageAccessDialog = { showUsageAccessDialog = false },
                        onGoToUsageAccessSettings = { openUsageAccessSettings() },
                        showNotificationPermissionRationale = showNotificationPermissionRationale,
                        onDismissNotificationPermissionRationale = { showNotificationPermissionRationale = false },
                        onRequestNotificationPermissionAgain = { requestNotificationPermission() },
                        apiKeyPresent = apiKeyPresent,
                        onSaveApiKey = {
                            ApiKeyManager.saveKey(this, it)
                            apiKeyPresent = true
                        },
                        showHeadsUpInfoDialog = showHeadsUpInfoDialog,
                        onDismissHeadsUpInfoDialog = {
                            showHeadsUpInfoDialog = false
                            startMonitoringServiceIfPermitted()
                        },
                        onGoToChannelSettings = { openNotificationChannelSettings() },
                        onOpenApps = { showAppsScreen = true },
                        onOpenTimers = { showTimerScreen = true },
                        onOpenLog = { showLogScreen = true },
                        userNotes = userNotes,
                        onSaveUserNotes = {
                            ApiKeyManager.saveUserNotes(this, it)
                            userNotes = it
                        },
                        coachName = coachName,
                        onSaveCoachName = {
                            ApiKeyManager.saveCoachName(this, it)
                            coachName = it
                        }
                    )
                }
            }
        }
        // Update monitoring state based on service status
        isMonitoringActive = isServiceRunning(AppUsageMonitorService::class.java)
    }

    override fun onResume() {
        super.onResume()
        // Re-check API key presence on resume
        apiKeyPresent = ApiKeyManager.hasKey(this)
        // Re-load user notes on resume
        userNotes = ApiKeyManager.getUserNotes(this)
        // Re-load coach name on resume
        coachName = ApiKeyManager.getCoachName(this)
        Log.d("MainActivity", "Coach name reloaded on resume: $coachName")
        // Re-check permissions if monitoring was supposed to be active
        if (isMonitoringActive && (!hasNotificationPermission() || !hasUsageStatsPermission())) {
            isMonitoringActive = false // Stop monitoring if permission revoked
            stopMonitoringService()
        }
    }

    private fun attemptStartMonitoring() {
        if (!isMonitoringActive) {
             // 0. Check API Key first
            if (!ApiKeyManager.hasKey(this)) {
                 Log.w("MainActivity", "API Key not found. Cannot start monitoring.")
                 // Optionally show a Toast or Snackbar message here
                 return // Stop the process if no key
            }
            // 1. Check Notification Permission first (Android 13+)
            if (hasNotificationPermission()) {
                 // 2. If Notification permission granted, check Usage Access
                checkAndRequestUsageAccess()
            } else {
                // Request Notification Permission
                requestNotificationPermission()
            }
        } else {
            stopMonitoringService()
            isMonitoringActive = false
        }
    }

    private fun checkAndRequestUsageAccess() {
         if (hasUsageStatsPermission()) {
            handlePermissionSuccess()
        } else { showUsageAccessDialog = true }
    }

    private fun handlePermissionSuccess() {
        // *** Check if info dialog has been shown before ***
        if (!ApiKeyManager.hasHeadsUpInfoBeenShown(this)) {
            // Show dialog AND set the flag
            showHeadsUpInfoDialog = true
            ApiKeyManager.setHeadsUpInfoShown(this)
            // Service start is deferred until dialog is dismissed
        } else {
             // Info already shown, proceed to start service directly
             Log.d("MainActivity", "Heads-up info previously shown, skipping dialog.")
             startMonitoringServiceIfPermitted()
        }
    }

    private fun startMonitoringServiceIfPermitted() {
        if (hasNotificationPermission() && hasUsageStatsPermission() && ApiKeyManager.hasKey(this)) {
            Log.i("MainActivity", "All permissions and API Key present. Starting Monitoring Service.")
            startMonitoringService() // The actual service start call
            isMonitoringActive = true
        } else {
            Log.w("MainActivity", "Attempted to start service without all permissions/key.")
            isMonitoringActive = false // Ensure state is correct
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU = API 33
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            return true // Automatically granted on older versions
        }
    }

     private fun requestNotificationPermission() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Show rationale if needed (user denied previously without 'never ask again')
                 showNotificationPermissionRationale = true
             } else {
                 // Request the permission
                 requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
             }
         }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        showUsageAccessDialog = false // Dismiss dialog before opening settings
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun openNotificationChannelSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, AppUsageMonitorService.CHANNEL_ID)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening channel settings", e)
            openAppNotificationSettings()
        }
        showHeadsUpInfoDialog = false
        startMonitoringServiceIfPermitted()
    }

    private fun openAppNotificationSettings() {
         val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            try {
                 startActivity(intent)
            } catch (e: Exception) {
                 Log.e("MainActivity", "Error opening app notification settings", e)
            }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        startForegroundService(intent)
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        stopService(intent)
    }

    // Helper to check if a service is running
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        // Use the modern approach for checking running services
        return manager.runningAppProcesses?.any { processInfo ->
            processInfo.processName == packageName && processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        } ?: false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeLinterApp(
    isMonitoring: Boolean,
    onToggleMonitoring: () -> Unit,
    showUsageAccessDialog: Boolean,
    onDismissUsageAccessDialog: () -> Unit,
    onGoToUsageAccessSettings: () -> Unit,
    showNotificationPermissionRationale: Boolean,
    onDismissNotificationPermissionRationale: () -> Unit,
    onRequestNotificationPermissionAgain: () -> Unit,
    apiKeyPresent: Boolean,
    onSaveApiKey: (String) -> Unit,
    showHeadsUpInfoDialog: Boolean,
    onDismissHeadsUpInfoDialog: () -> Unit,
    onGoToChannelSettings: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenLog: () -> Unit,
    userNotes: String,
    onSaveUserNotes: (String) -> Unit,
    coachName: String,
    onSaveCoachName: (String) -> Unit
) {
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var userNotesInput by rememberSaveable { mutableStateOf(userNotes) }
    var coachNameInput by rememberSaveable { mutableStateOf(coachName) }
    
    // Update local state when props change
    LaunchedEffect(userNotes) {
        userNotesInput = userNotes
    }
    LaunchedEffect(coachName) {
        coachNameInput = coachName
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    // Apps button
                    IconButton(onClick = onOpenApps) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Manage Apps")
                    }
                    // Timer settings button
                    IconButton(onClick = onOpenTimers) {
                        Icon(Icons.Default.Timer, contentDescription = "Timer Settings")
                    }
                    // AI Log button
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Default.History, contentDescription = "AI Log")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // --- Coach Name Greeting Section ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hi I'm ",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = coachNameInput,
                    onValueChange = { coachNameInput = it },
                    label = { Text("Name") },
                    placeholder = { Text("Adam") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp)
                )
            }
            if (coachNameInput != coachName && coachNameInput.isNotBlank()) {
                Button(
                    onClick = { onSaveCoachName(coachNameInput) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Save")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // --- API Key Input Section (Show if key not present) ---
            if (!apiKeyPresent) {
                val backgroundColor = MaterialTheme.colorScheme.errorContainer;
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val context = LocalContext.current

                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Gemini API Key Required",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Button(onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    "https://aistudio.google.com/apikey".toUri()
                                )
                                context.startActivity(intent)
                            }) {
                                Text("Get API Key")
                            }

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("Enter API Key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = { onSaveApiKey(apiKeyInput) },
                                enabled = apiKeyInput.isNotBlank()
                            ) {
                                Text("Save API Key")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp)) // Add space after key section
            }

            // --- Status recap section ---
            Spacer(modifier = Modifier.height(16.dp))

            val statusText: String
            val backgroundColor: Color
            val textColor: Color

            when {
                !apiKeyPresent -> {
                    statusText = "Please enter your API Key above."
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                }
                isMonitoring -> {
                    statusText = "Monitoring active..."
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer
                }
                else -> {
                    statusText = "Monitoring stopped."
                    backgroundColor = MaterialTheme.colorScheme.errorContainer
                    textColor = MaterialTheme.colorScheme.onErrorContainer
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // --- User Notes Section ---
            Text(
                text = "Personal Notes for AI",
                style = MaterialTheme.typography.titleMedium
            )
            ScrollableTextFieldWithScrollbar (
                value = userNotesInput,
                onValueChange = { userNotesInput = it },
                label = "Add context or goals for the AI..." ,
                // placeholder = { Text("e.g., I'm trying to focus on work, help me stay productive") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // Make it expand
                // minLines = 8 // Increase default size, allows it to grow beyond this too
            )
            Button(
                onClick = { onSaveUserNotes(userNotesInput) },
                enabled = userNotesInput != userNotes // Enable only if text has changed
            ) {
                Text("Save Notes")
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onToggleMonitoring,
                enabled = apiKeyPresent,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isMonitoring) "Stop Monitoring" else "Start Monitoring")
            }

            // Dialog for Usage Access Permission
            if (showUsageAccessDialog) {
                AlertDialog(
                    onDismissRequest = onDismissUsageAccessDialog,
                    title = { Text("Usage Access Required") },
                    text = { Text("Time Linter needs Usage Access permission to monitor app usage. Please grant it in the system settings.") },
                    confirmButton = {
                        Button(onClick = onGoToUsageAccessSettings) {
                            Text("Go to Settings")
                        }
                    },
                    dismissButton = {
                        Button(onClick = onDismissUsageAccessDialog) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Dialog for Notification Permission Rationale
            if (showNotificationPermissionRationale) {
                 AlertDialog(
                    onDismissRequest = onDismissNotificationPermissionRationale,
                    title = { Text("Notification Permission Needed") },
                    text = { Text("Notifications are required for Time Linter to function correctly, especially the persistent status notification. Please grant the permission to enable monitoring.") },
                    confirmButton = {
                        Button(onClick = onRequestNotificationPermissionAgain) {
                            Text("Request Again")
                        }
                    },
                     dismissButton = {
                         Button(onClick = onDismissNotificationPermissionRationale) {
                             Text("Cancel")
                         }
                     }
                )
            }

            // Dialog for Heads-Up/Pop-on-screen Info
            if (showHeadsUpInfoDialog) {
                AlertDialog(
                    onDismissRequest = onDismissHeadsUpInfoDialog,
                    title = { Text("Notification Style Suggestion") },
                    text = { Text("For the best experience, allow Time Linter's 'Conversation' notifications to 'Pop on screen'. This lets the AI reply appear immediately over other apps. You can check this in the channel settings.") },
                    confirmButton = {
                        Button(onClick = onGoToChannelSettings) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        Button(onClick = onDismissHeadsUpInfoDialog) {
                            Text("Maybe Later")
                        }
                    }
                )
            }
        }
    }
}
