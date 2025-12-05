package com.example.timelinter

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

@OptIn(ExperimentalTime::class)
@Composable
fun AILogScreen(
    onNavigateBack: () -> Unit
) {
    val events by EventLogStore.events.collectAsState()
    val aiMemory by ConversationLogStore.aiMemory.collectAsState()
    val context = LocalContext.current

    // Ensure memory is initialized when opening this screen
    LaunchedEffect(Unit) {
        val current = AIMemoryManager.getAllMemories(context)
        ConversationLogStore.setMemory(current)
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

    Scaffold(
        topBar = {
            com.example.timelinter.ui.components.AppTopBar(
                title = "AI Log",
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
                .padding(16.dp)
        ) {
            // Rules editor (small)
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

            Spacer(Modifier.height(16.dp))
            Text(text = "Permanent AI Memory", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)) {
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

            // Temporary memory groups
            Text(text = "Temporary AI Memory", style = MaterialTheme.typography.titleMedium)
            val dateTimeFormatter = remember {
                LocalDateTime.Format {
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
            }
            tempGroups.forEach { group ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("tempMemoryGroup-${group.expirationDateKey}")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 1. This part is already correct
                        val localDateTime = group.expirationDateKey.toLocalDateTime(timeZone = TimeZone.currentSystemDefault())

                        // 2. Format using the correct kotlinx formatter. This now works.
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
            Spacer(Modifier.height(16.dp))
            var query by remember { mutableStateOf("") }
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
            val filtered = remember(events, query) {
                if (query.isBlank()) events else EventLogStore.search(query)
            }
            if (filtered.isEmpty()) {
                Text(
                    text = "No events yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(filtered) { index: Int, entry ->
                        // Format timestamp for display
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
    }
}


