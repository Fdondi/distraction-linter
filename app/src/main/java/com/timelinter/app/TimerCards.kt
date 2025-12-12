package com.timelinter.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun LogDurationCard(
    title: String,
    description: String,
    value: Duration,
    min: Duration,
    max: Duration,
    steps: Int = 100,
    onValueChange: (Duration) -> Unit
) {
    val sliderValue = durationToSlider(value, min, max)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDurationShort(value))
                Slider(
                    value = sliderValue,
                    onValueChange = { raw ->
                        val clamped = raw.coerceIn(0f, 1f)
                        onValueChange(sliderToDuration(clamped, min, max))
                    },
                    valueRange = 0f..1f,
                    steps = steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LinearDurationCard(
    title: String,
    description: String,
    value: Duration,
    valueRangeMinutes: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Duration) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${value.inWholeMinutes} min")
                Slider(
                    value = value.inWholeMinutes.toFloat(),
                    onValueChange = { minutes ->
                        onValueChange(minutes.toInt().minutes)
                    },
                    valueRange = valueRangeMinutes,
                    steps = steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LinearFloatCard(
    title: String,
    description: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(valueText)
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

fun sliderToDuration(value: Float, min: Duration, max: Duration): Duration {
    val clamped = value.coerceIn(0f, 1f).toDouble()
    val minMs = min.inWholeMilliseconds.toDouble()
    val ratio = max.inWholeMilliseconds.toDouble() / minMs
    val scaledMs = minMs * ratio.pow(clamped)
    return scaledMs.toLong().toDuration(DurationUnit.MILLISECONDS)
}

fun durationToSlider(duration: Duration, min: Duration, max: Duration): Float {
    val minMs = min.inWholeMilliseconds.toDouble()
    val maxMs = max.inWholeMilliseconds.toDouble()
    val targetMs = duration.coerceIn(min, max).inWholeMilliseconds.toDouble()
    val ratio = maxMs / minMs
    val normalized = ln(targetMs / minMs) / ln(ratio)
    return normalized.toFloat().coerceIn(0f, 1f)
}

fun formatDurationShort(duration: Duration): String {
    return when {
        duration < 120.seconds -> "${duration.inWholeSeconds} s"
        else -> "${duration.inWholeMinutes} min"
    }
}

