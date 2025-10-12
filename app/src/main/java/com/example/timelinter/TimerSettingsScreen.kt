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

/**
 * Dedicated screen for configuring all timer-related settings. Extracted from the old SettingsScreen.
 */
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
    
    // Good Apps settings
    var maxOverfillMinutes by remember { mutableStateOf(SettingsManager.getMaxOverfillMinutes(context)) }
    var overfillDecayPerHourMinutes by remember { mutableStateOf(SettingsManager.getOverfillDecayPerHourMinutes(context)) }
    var goodAppRewardIntervalMinutes by remember { mutableStateOf(SettingsManager.getGoodAppRewardIntervalMinutes(context)) }
    var goodAppRewardAmountMinutes by remember { mutableStateOf(SettingsManager.getGoodAppRewardAmountMinutes(context)) }

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

            // Divider and Good Apps section
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Text(
                "Good Apps Rewards",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Good App Reward Interval
            TimerCard(
                title = "Good App Reward Interval",
                description = "How many minutes of using good apps are needed to earn a reward",
                valueText = "${goodAppRewardIntervalMinutes} min",
                sliderValue = goodAppRewardIntervalMinutes,
                valueRange = 1f..60f,
                steps = 59,
                onValueChange = { value ->
                    goodAppRewardIntervalMinutes = value
                    SettingsManager.setGoodAppRewardIntervalMinutes(context, goodAppRewardIntervalMinutes)
                }
            )

            // Good App Reward Amount
            TimerCard(
                title = "Good App Reward Amount",
                description = "How many bonus minutes you earn per reward interval",
                valueText = "${goodAppRewardAmountMinutes} min",
                sliderValue = goodAppRewardAmountMinutes,
                valueRange = 1f..30f,
                steps = 29,
                onValueChange = { value ->
                    goodAppRewardAmountMinutes = value
                    SettingsManager.setGoodAppRewardAmountMinutes(context, goodAppRewardAmountMinutes)
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
                    SettingsManager.setMaxOverfillMinutes(context, maxOverfillMinutes)
                }
            )

            // Overfill Decay
            TimerCard(
                title = "Overfill Decay Rate",
                description = "How many minutes of bonus time decay per hour when not using good apps",
                valueText = "${overfillDecayPerHourMinutes} min/hour",
                sliderValue = overfillDecayPerHourMinutes,
                valueRange = 0f..30f,
                steps = 30,
                onValueChange = { value ->
                    overfillDecayPerHourMinutes = value
                    SettingsManager.setOverfillDecayPerHourMinutes(context, overfillDecayPerHourMinutes)
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
                                "Good Apps:\n" +
                                "• Reward Interval: How long you need to use good apps to earn a reward\n" +
                                "• Reward Amount: Bonus time earned per interval (can exceed your normal limit)\n" +
                                "• Max Overfill: Maximum bonus time you can store beyond your limit\n" +
                                "• Decay Rate: How fast bonus time decays when not using good apps",
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