package com.example.timelinter

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Dedicated screen for configuring all timer-related settings. Extracted from the old SettingsScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // State holders backed by SettingsManager
    var observeTimerMinutes by remember { mutableStateOf(SettingsManager.getObserveTimerMinutes(context)) }
    var responseTimerMinutes by remember { mutableStateOf(SettingsManager.getResponseTimerMinutes(context)) }
    var maxThresholdMinutes by remember { mutableStateOf(SettingsManager.getMaxThresholdMinutes(context)) }
    var replenishAmountMinutes by remember { mutableStateOf(SettingsManager.getReplenishAmountMinutes(context)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Timer Settings") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Observe Timer Setting
            TimerCard(
                title = "Observe Timer",
                description = "How long to wait before checking if you're wasting time again",
                valueText = "${observeTimerMinutes} minutes",
                sliderValue = observeTimerMinutes,
                valueRange = 1f..30f,
                steps = 28,
                onValueChange = { value ->
                    observeTimerMinutes = value
                    SettingsManager.setObserveTimerMinutes(context, observeTimerMinutes)
                    // Keep replenish interval in sync
                    SettingsManager.setReplenishIntervalMinutes(context, observeTimerMinutes)
                }
            )

            // Response Timer Setting
            TimerCard(
                title = "Response Timer",
                description = "How long to wait for your response before considering it ignored",
                valueText = "${responseTimerMinutes} minute${if (responseTimerMinutes > 1) "s" else ""}",
                sliderValue = responseTimerMinutes,
                valueRange = 1f..10f,
                steps = 8,
                onValueChange = { value ->
                    responseTimerMinutes = value
                    SettingsManager.setResponseTimerMinutes(context, responseTimerMinutes)
                }
            )

            // Max Threshold Minutes Setting
            TimerCard(
                title = "Max Allowed Minutes",
                description = "How much time you can spend in wasteful apps before intervention (bucket size)",
                valueText = "${maxThresholdMinutes} min",
                sliderValue = maxThresholdMinutes,
                valueRange = 1f..60f,
                steps = 59,
                onValueChange = { value ->
                    maxThresholdMinutes = value
                    SettingsManager.setMaxThresholdMinutes(context, maxThresholdMinutes)
                }
            )

            // Replenish Amount Minutes Setting
            TimerCard(
                title = "Replenish Amount",
                description = "How many minutes are restored to your allowance each interval you stay off wasteful apps",
                valueText = "${replenishAmountMinutes} min",
                sliderValue = replenishAmountMinutes,
                valueRange = 1f..10f,
                steps = 9,
                onValueChange = { value ->
                    replenishAmountMinutes = value
                    SettingsManager.setReplenishAmountMinutes(context, replenishAmountMinutes)
                }
            )

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("How Timers Work", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Observe Timer: After you stop using a time-wasting app, Time Linter waits this long before checking again (also used as the replenish interval)\n" +
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

@Composable
private fun TimerCard(
    title: String,
    description: String,
    valueText: String,
    sliderValue: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(valueText)
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { onValueChange(it.toInt()) },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
} 