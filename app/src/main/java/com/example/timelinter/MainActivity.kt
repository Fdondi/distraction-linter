package com.example.timelinter

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {

    private var showPermissionDialog by mutableStateOf(false)
    private var isMonitoringActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TimeLinterApp(
                    isMonitoring = isMonitoringActive,
                    onToggleMonitoring = { attemptStartMonitoring() },
                    showPermissionDialog = showPermissionDialog,
                    onDismissPermissionDialog = { showPermissionDialog = false },
                    onGoToSettings = { openUsageAccessSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check if monitoring was active and permission is now granted
        if (isMonitoringActive && !hasUsageStatsPermission()) {
            isMonitoringActive = false // Stop monitoring if permission revoked
            stopMonitoringService()
        }
    }

    private fun attemptStartMonitoring() {
        if (!isMonitoringActive) {
            if (hasUsageStatsPermission()) {
                startMonitoringService()
                isMonitoringActive = true
            } else {
                showPermissionDialog = true
            }
        } else {
            stopMonitoringService()
            isMonitoringActive = false
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
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
        showPermissionDialog = false // Dismiss dialog after opening settings
    }

    private fun startMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        startService(intent)
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        stopService(intent)
    }
}

@Composable
fun TimeLinterApp(
    isMonitoring: Boolean,
    onToggleMonitoring: () -> Unit,
    showPermissionDialog: Boolean,
    onDismissPermissionDialog: () -> Unit,
    onGoToSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onToggleMonitoring) {
            Text(if (isMonitoring) "Stop Monitoring" else "Start Monitoring")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isMonitoring) "Monitoring active..." else "Monitoring stopped.",
            style = MaterialTheme.typography.bodyLarge
        )

        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = onDismissPermissionDialog,
                title = { Text("Permission Required") },
                text = { Text("Time Linter needs Usage Access permission to monitor app usage. Please grant it in the system settings.") },
                confirmButton = {
                    Button(onClick = onGoToSettings) {
                        Text("Go to Settings")
                    }
                },
                dismissButton = {
                    Button(onClick = onDismissPermissionDialog) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
