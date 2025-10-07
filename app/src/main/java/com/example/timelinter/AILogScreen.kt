package com.example.timelinter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// Use fully qualified types to avoid import resolution issues in some environments
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (!isEditing) {
                        Text(
                            text = if (aiMemory.isBlank()) "(No AI memory yet)" else aiMemory,
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
            val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
            tempGroups.forEach { group ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("tempMemoryGroup-${group.expirationDateKey}")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val labelDate = LocalDate.parse(group.expirationDateKey)
                        Text("Expires on ${labelDate.format(dateFormatter)}", style = MaterialTheme.typography.titleSmall)
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
                        val headline = when (entry.type) {
                            EventType.MESSAGE -> "${index + 1}. ${entry.title}"
                            EventType.TOOL -> "${index + 1}. ${entry.title}"
                            EventType.STATE -> "${index + 1}. ${entry.title}"
                            EventType.APP -> "${index + 1}. ${entry.title}"
                            EventType.BUCKET -> "${index + 1}. ${entry.title}"
                            EventType.SYSTEM -> "${index + 1}. ${entry.title}"
                        }
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = headline,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            },
                            overlineContent = {
                                Text(
                                    text = entry.type.name,
                                    style = MaterialTheme.typography.labelSmall
                                )
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


