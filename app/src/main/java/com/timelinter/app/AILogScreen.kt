package com.timelinter.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTime::class)
@Composable
fun AILogScreen(
    onNavigateBack: () -> Unit
) {
    val events by EventLogStore.events.collectAsState()
    val aiMemory by ConversationLogStore.aiMemory.collectAsState()
    val temporaryAllows by TemporaryAllowStore.allows.collectAsState()
    val context = LocalContext.current

    // Ensure memory is initialized when opening this screen
    LaunchedEffect(Unit) {
        val current = AIMemoryManager.getAllMemories(context)
        ConversationLogStore.setMemory(current)
    }

    LaunchedEffect(Unit) {
        TemporaryAllowStore.refresh(context)
    }

    var isEditing by remember { mutableStateOf(false) }
    var editorText by remember(aiMemory) { mutableStateOf(aiMemory) }

    // Rules state
    var rulesText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rulesText = AIMemoryManager.getMemoryRules(context)
    }

    // Temporary groups state
    var tempGroups by remember { mutableStateOf(listOf<AIMemoryManager.TemporaryGroupByDate>()) }
    LaunchedEffect(aiMemory) {
        tempGroups = AIMemoryManager.getActiveTemporaryGroupsByDate(context)
    }

    var query by remember { mutableStateOf("") }
    val filteredEvents by remember(events, query) {
        derivedStateOf {
            if (query.isBlank()) events else EventLogStore.search(query)
        }
    }

    fun durationLabel(duration: Duration): String {
        val totalMinutes = duration.inWholeMinutes
        val hours = totalMinutes / 60
        val minutesRemainder = totalMinutes % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            append("${minutesRemainder}m")
        }
    }

    fun allowTagSuffix(name: String?): String {
        val base = name ?: "all_apps"
        return base.replace("\\W+".toRegex(), "_")
    }

    Scaffold(
        topBar = {
            com.timelinter.app.ui.components.AppTopBar(
                title = "AI Log",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section header for AI memory content (needed for tests and clarity)
            item {
                Text(
                    text = "AI Memory",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            // Rules editor (small)
            item {
                Text(text = "AI Memory Rules", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = rulesText,
                    onValueChange = { rulesText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("aiMemoryRules"),
                    maxLines = 4,
                    minLines = 2,
                    label = { Text("Guidelines for remember() usage") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        AIMemoryManager.setMemoryRules(context, rulesText)
                    }) {
                        Text("Save Rules")
                    }
                }
            }

            // Permanent AI Memory
            item {
                Text(text = "Permanent AI Memory", style = MaterialTheme.typography.titleMedium)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (!isEditing) {
                            Text(
                                text = aiMemory.ifBlank { "(No AI memory yet)" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    editorText = aiMemory
                                    isEditing = true
                                }) {
                                    Text("Edit")
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = editorText,
                                onValueChange = { editorText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp)
                                    .testTag("aiMemoryEditor"),
                                minLines = 4,
                                label = { Text("Permanent AI memory") }
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    AIMemoryManager.setPermanentMemory(context, editorText)
                                    val updated = AIMemoryManager.getAllMemories(context)
                                    ConversationLogStore.setMemory(updated)
                                    isEditing = false
                                }) {
                                    Text("Save")
                                }
                                OutlinedButton(onClick = {
                                    isEditing = false
                                    editorText = aiMemory
                                }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // Temporary Allows
            item {
                Text(text = "Temporary Allows", style = MaterialTheme.typography.titleMedium)
            }
            if (temporaryAllows.isEmpty()) {
                item {
                    Text(
                        text = "No temporary allows are active.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(temporaryAllows, key = { "${it.appName ?: "all"}-${it.expiresAt.toEpochMilliseconds()}" }) { allow ->
                    val tagSuffix = allowTagSuffix(allow.appName)
                    val remaining = allow.remainingDuration(SystemTimeProvider.now())
                    var minutesText by remember(allow) {
                        mutableStateOf(
                            remaining.inWholeMinutes.coerceAtLeast(1).toString()
                        )
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tempAllowRow-$tagSuffix")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = allow.appName ?: "All wasteful apps",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Expires in ${durationLabel(remaining)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = minutesText,
                                onValueChange = { newValue ->
                                    minutesText = newValue.filter { it.isDigit() }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tempAllowInput-$tagSuffix"),
                                singleLine = true,
                                label = { Text("Minutes from now") }
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val minutesValue = minutesText.toLongOrNull()
                                        if (minutesValue != null && minutesValue > 0) {
                                            TemporaryAllowStore.upsertAllow(
                                                context,
                                                allow.appName,
                                                minutesValue.minutes
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("tempAllowSave-$tagSuffix")
                                ) {
                                    Text("Update")
                                }
                                OutlinedButton(
                                    onClick = {
                                        TemporaryAllowStore.removeAllow(context, allow.appName)
                                    },
                                    modifier = Modifier.testTag("tempAllowRemove-$tagSuffix")
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            // Temporary memory groups
            item {
                Text(text = "Temporary AI Memory", style = MaterialTheme.typography.titleMedium)
            }
            val dateTimeFormatter = LocalDateTime.Format {
                year()
                char('-')
                monthNumber(padding = Padding.ZERO)
                char('-')
                day(padding = Padding.ZERO)
                char(' ')
                hour(padding = Padding.ZERO)
                char(':')
                minute(padding = Padding.ZERO)
            }
            items(tempGroups, key = { it.expirationDateKey.toEpochMilliseconds() }) { group ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tempMemoryGroup-${group.expirationDateKey}")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val localDateTime = group.expirationDateKey.toLocalDateTime(timeZone = TimeZone.currentSystemDefault())
                        Text(
                            "Expires on ${localDateTime.format(dateTimeFormatter)}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        group.items.forEach { item ->
                            Text("• $item", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Event log with search
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("eventLogSearch"),
                    label = { Text("Search events") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                if (filteredEvents.isEmpty()) {
                    Text(
                        text = "No events yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            itemsIndexed(filteredEvents) { _, entry ->
                val timestamp = remember(entry.timestamp) {
                    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    formatter.format(Date(entry.timestamp))
                }

                ListItem(
                    headlineContent = {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    overlineContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.type.name,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    },
                    supportingContent = {
                        val roleSuffix = entry.role?.let { " ($it)" } ?: ""
                        Text(
                            text = (entry.details ?: "") + roleSuffix,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}


