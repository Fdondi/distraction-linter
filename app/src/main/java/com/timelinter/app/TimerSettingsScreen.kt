package com.timelinter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider as MaterialDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Dedicated screen for configuring all timer-related settings. Extracted from the old SettingsScreen.
 */
@Composable
fun TimerSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // State holders backed by SettingsManager
    val responseMin = 10.seconds
    val responseMax = 10.minutes
    var responseSlider by remember {
        mutableStateOf<Float>(
            durationToSlider(SettingsManager.getResponseTimer(context), responseMin, responseMax)
        )
    }
    val responseDuration: Duration = sliderToDuration(responseSlider, responseMin, responseMax)
    var maxThreshold by remember { mutableStateOf(SettingsManager.getMaxThreshold(context)) }
    var replenishRateFraction by remember { mutableFloatStateOf(SettingsManager.getReplenishRateFraction(context)) }
    val wakeupMin = 10.seconds
    val wakeupMax = 10.minutes
    var wakeupSlider by remember {
        mutableStateOf<Float>(
            durationToSlider(SettingsManager.getWakeupInterval(context), wakeupMin, wakeupMax)
        )
    }
    val wakeupInterval: Duration = sliderToDuration(wakeupSlider, wakeupMin, wakeupMax)
    
    // Good Apps settings
    var maxOverfill by remember { mutableStateOf(SettingsManager.getMaxOverfill(context)) }
    var overfillDecayPerHour by remember { mutableStateOf(SettingsManager.getOverfillDecayPerHour(context)) }
    var goodAppFillRateMultiplier by remember { mutableStateOf(SettingsManager.getGoodAppFillRateMultiplier(context)) }

    Scaffold(
        topBar = {
            com.timelinter.app.ui.components.AppTopBar(
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
            // Section header for timer observation (used by tests and UX clarity)
            Text(
                text = "Observe Timer",
                style = MaterialTheme.typography.headlineSmall
            )

            // Wakeup Interval Setting (log scale 10s-10m)
            LogDurationCard(
                title = "Wakeup Interval",
                description = "How often the service wakes to check the current app",
                value = wakeupInterval,
                min = wakeupMin,
                max = wakeupMax
            ) { duration ->
                wakeupSlider = durationToSlider(duration, wakeupMin, wakeupMax)
                SettingsManager.setWakeupInterval(context, duration)
            }

            // Response Timer Setting (log scale 10s-10m)
            LogDurationCard(
                title = "Response Timer",
                description = "How long to wait for your response before considering it ignored",
                value = responseDuration,
                min = responseMin,
                max = responseMax
            ) { duration ->
                responseSlider = durationToSlider(duration, responseMin, responseMax)
                SettingsManager.setResponseTimer(context, duration)
            }

            // Max Threshold Minutes Setting
            LinearDurationCard(
                title = "Max Allowed Minutes",
                description = "How much time you can spend in wasteful apps before intervention (bucket size)",
                value = maxThreshold,
                valueRangeMinutes = 1f..60f,
                steps = 59
            ) { value ->
                maxThreshold = value
                SettingsManager.setMaxThreshold(context, maxThreshold)
            }

            // Replenish Rate Setting
            LinearDurationCard(
                title = "Replenish Rate",
                description = "Fraction of an hour restored when off wasteful apps (0.1 = 6 min/hour)",
                value = (replenishRateFraction * 60.0).minutes,
                valueRangeMinutes = 0f..120f,
                steps = 120
            ) { value ->
                val fraction = (value.inWholeSeconds.toFloat() / 3600f).coerceIn(0f, 2f)
                replenishRateFraction = fraction
                SettingsManager.setReplenishRateFraction(context, fraction)
            }

            // Divider and Good Apps section
            MaterialDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            Text(
                text = "Good Apps Rewards",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Good App Fill Rate Multiplier
            LinearFloatCard(
                title = "Good App Fill Rate",
                description = "How much faster good apps fill your bucket (e.g., 2.0 = twice as fast)",
                valueText = "${"%.1f".format(goodAppFillRateMultiplier)}x",
                value = goodAppFillRateMultiplier,
                valueRange = 0f..10f,
                steps = 45,
                onValueChange = { value ->
                    goodAppFillRateMultiplier = value
                    SettingsManager.setGoodAppFillRateMultiplier(context, goodAppFillRateMultiplier)
                }
            )

            // Max Overfill
            LinearDurationCard(
                title = "Max Overfill",
                description = "Maximum bonus time you can accumulate beyond your normal limit",
                value = maxOverfill,
                valueRangeMinutes = 0f..60f,
                steps = 60
            ) { value ->
                maxOverfill = value
                SettingsManager.setMaxOverfill(context, maxOverfill)
            }

            // Overfill Decay
            LinearDurationCard(
                title = "Overfill Decay Rate",
                description = "How many minutes of bonus time decay per hour when not using good apps",
                value = overfillDecayPerHour,
                valueRangeMinutes = 0f..30f,
                steps = 30
            ) { value ->
                overfillDecayPerHour = value
                SettingsManager.setOverfillDecayPerHour(context, overfillDecayPerHour)
            }

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "How Timers Work", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Wakeup Interval: How often the service wakes up to check the current app (log scaled 10s–10m)." +
                                "• Response Timer: When Time Linter sends you a message, it waits this long for your reply before sending a follow-up." +
                                "• Max Allowed Minutes: The total time you can spend in wasteful apps before intervention (bucket size)." +
                                "• Replenish Rate: How many minutes are restored per hour you stay off wasteful apps." +
                                " Unified Bucket System:" +
                                "• Good App Fill Rate: How much faster good apps fill your bucket (can exceed normal limit)." +
                                "• Neutral App Fill Rate: How fast neutral apps fill your bucket." +
                                "• Max Overfill: Maximum extra time you can store beyond your normal limit." +
                                "• Decay Rate: How fast overfill decays when not using good apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
