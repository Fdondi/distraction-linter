package com.example.timelinter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.minutes

/**
 * Dedicated screen for configuring all timer-related settings. Extracted from the old SettingsScreen.
 */
@Composable
fun TimerSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // State holders backed by SettingsManager
    var observeTimer by remember { mutableStateOf(SettingsManager.getObserveTimer(context)) }
    var responseTimer by remember { mutableStateOf(SettingsManager.getResponseTimer(context)) }
    var maxThreshold by remember { mutableStateOf(SettingsManager.getMaxThreshold(context)) }
    var replenishAmount by remember { mutableStateOf(SettingsManager.getReplenishAmount(context)) }
    
    // Good Apps settings
    var maxOverfill by remember { mutableStateOf(SettingsManager.getMaxOverfill(context)) }
    var overfillDecayPerHour by remember { mutableStateOf(SettingsManager.getOverfillDecayPerHour(context)) }
    var goodAppFillRateMultiplier by remember { mutableStateOf(SettingsManager.getGoodAppFillRateMultiplier(context)) }

    Scaffold(
        topBar = {
            com.example.timelinter.ui.components.AppTopBar(
                title = "Timer Settings",
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Observe Timer Setting
            TimerCard(
                title = "Observe Timer",
                description = "How long to wait before checking if you're wasting time again",
                valueText = "${observeTimer.inWholeMinutes} minutes",
                sliderValue = observeTimer.inWholeMinutes,
                valueRange = 1f..30f,
                steps = 28,
                onValueChange = { value ->
                    observeTimer = value.minutes
                    SettingsManager.setObserveTimer(context, observeTimer)
                    // Keep replenish interval in sync
                    SettingsManager.setReplenishInterval(context, observeTimer)
                }
            )

            // Response Timer Setting
            TimerCard(
                title = "Response Timer",
                description = "How long to wait for your response before considering it ignored",
                valueText = "${responseTimer.inWholeMinutes} minutes",
                sliderValue = responseTimer.inWholeMinutes,
                valueRange = 1f..10f,
                steps = 8,
                onValueChange = { value ->
                    responseTimer = value.minutes
                    SettingsManager.setResponseTimer(context, responseTimer)
                }
            )

            // Max Threshold Minutes Setting
            TimerCard(
                title = "Max Allowed Minutes",
                description = "How much time you can spend in wasteful apps before intervention (bucket size)",
                valueText = "${maxThreshold.inWholeMinutes} min",
                sliderValue = maxThreshold.inWholeMinutes,
                valueRange = 1f..60f,
                steps = 59,
                onValueChange = { value ->
                    maxThreshold = value.minutes
                    SettingsManager.setMaxThreshold(context, maxThreshold)
                }
            )

            // Replenish Amount Minutes Setting
            TimerCard(
                title = "Replenish Amount",
                description = "How many minutes are restored to your allowance each interval you stay off wasteful apps",
                valueText = "${replenishAmount.inWholeMinutes} min",
                sliderValue = replenishAmount.inWholeMinutes.toInt(),
                valueRange = 1f..10f,
                steps = 9,
                onValueChange = { value ->
                    replenishAmount = value.minutes
                    SettingsManager.setReplenishAmount(context, replenishAmount)
                }
            )

            // Divider and Good Apps section
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Text(
                "Good Apps Rewards",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Good App Fill Rate Multiplier
            TimerCard(
                title = "Good App Fill Rate",
                description = "How much faster good apps fill your bucket (e.g., 2.0 = twice as fast)",
                valueText = "${"%.1f".format(goodAppFillRateMultiplier)}x",
                sliderValue = goodAppFillRateMultiplier,
                valueRange = 0f..10f,
                steps = 45,
                onValueChange = { value ->
                    goodAppFillRateMultiplier = value
                    SettingsManager.setGoodAppFillRateMultiplier(context, goodAppFillRateMultiplier)
                }
            )

            // Max Overfill
            TimerCard(
                title = "Max Overfill",
                description = "Maximum bonus time you can accumulate beyond your normal limit",
                valueText = "${maxOverfillMinutes} min",
                sliderValue = maxOverfillMinutes,
                valueRange = 0f..60f,
                steps = 60,
                onValueChange = { value ->
                    maxOverfillMinutes = value
                    SettingsManager.setMaxOverfill(context, maxOverfillMinutes)
                }
            )

            // Overfill Decay
            TimerCard(
                title = "Overfill Decay Rate",
                description = "How many minutes of bonus time decay per hour when not using good apps",
                valueText = "${overfillDecayPerHour.inWholeMinutes} min/hour",
                sliderValue = overfillDecayPerHour.inWholeMinutes,
                valueRange = 0f..30f,
                steps = 30,
                onValueChange = { value ->
                    overfillDecayPerHour = value.minutes
                    SettingsManager.setOverfillDecayPerHour(context, overfillDecayPerHour)
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
                                "• Replenish Amount: How much time is restored to your allowance each interval you stay off wasteful apps (interval = Observe Timer)\n\n" +
                                "Unified Bucket System:\n" +
                                "• Good App Fill Rate: How much faster good apps fill your bucket (can exceed normal limit)\n" +
                                "• Neutral App Fill Rate: How fast neutral apps fill your bucket\n" +
                                "• Max Overfill: Maximum extra time you can store beyond your normal limit\n" +
                                "• Decay Rate: How fast overfill decays when not using good apps",
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