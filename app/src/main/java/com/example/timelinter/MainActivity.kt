package com.example.timelinter

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var showUsageAccessDialog by mutableStateOf(false)
    private var showNotificationPermissionRationale by mutableStateOf(false)
    private var showHeadsUpInfoDialog by mutableStateOf(false)
    private var isMonitoringActive by mutableStateOf(false)
    private var apiKeyPresent by mutableStateOf(false)

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

        setContent {
            MaterialTheme {
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
                    onGoToChannelSettings = { openNotificationChannelSettings() }
                )
            }
        }
        // Update monitoring state based on service status
        isMonitoringActive = isServiceRunning(AppUsageMonitorService::class.java)
    }

    override fun onResume() {
        super.onResume()
        // Re-check API key presence on resume
        apiKeyPresent = ApiKeyManager.hasKey(this)
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
            // All permissions granted, now check if we should show Heads-Up info
            handlePermissionSuccess()
        } else {
            // Show dialog to guide user to Usage Access settings
            showUsageAccessDialog = true
        }
    }

    private fun handlePermissionSuccess() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On Oreo+, suggest checking channel settings for pop-up behavior
            showHeadsUpInfoDialog = true
            // Service start is deferred until dialog is dismissed
         } else {
            // On older versions, just start the service
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
        val mode = appOps.checkOpNoThrow(
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        } else {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             startForegroundService(intent) // Use foreground start for O+
        } else {
             startService(intent)
        }
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        stopService(intent)
    }

    // Helper to check if a service is running (basic check)
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                if (service.foreground) { // Check if it's a foreground service
                     return true
                }
            }
        }
        return false
    }
}

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
    onGoToChannelSettings: () -> Unit
) {
    var apiKeyInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- API Key Input Section (Show if key not present) ---
        if (!apiKeyPresent) {
            Text(
                text = "Gemini API Key Required",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("Enter API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(), // Hide the key
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSaveApiKey(apiKeyInput) },
                enabled = apiKeyInput.isNotBlank() // Enable only if text is entered
            ) {
                Text("Save API Key")
            }
            Spacer(modifier = Modifier.height(32.dp)) // Add space after key section
        }

        // --- Original UI Elements --- 
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp)) // Adjusted spacing

        Button(
            onClick = onToggleMonitoring,
            enabled = apiKeyPresent
        ) {
            Text(if (isMonitoring) "Stop Monitoring" else "Start Monitoring")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when {
                 !apiKeyPresent -> "Please enter your API Key above."
                 isMonitoring -> "Monitoring active..."
                 else -> "Monitoring stopped."
            },
            style = MaterialTheme.typography.bodyLarge
        )

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
