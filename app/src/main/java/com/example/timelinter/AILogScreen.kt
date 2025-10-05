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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AILogScreen(
    onNavigateBack: () -> Unit
) {
    val history by ConversationLogStore.apiHistory.collectAsState()
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
            CenterAlignedTopAppBar(
                title = { Text("AI Log") },
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

            if (history.isEmpty()) {
                Text(
                    text = "No AI conversation yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(history) { index: Int, content ->
                        val role = content.role ?: "unknown"
                        val text = content.parts.joinToString("\n") { part ->
                            when (part) {
                                is com.google.ai.client.generativeai.type.TextPart -> part.text
                                else -> part.toString()
                            }
                        }

                        val isSeparator = text.startsWith("------ NEW CONVERSATION:")

                        if (isSeparator) {
                            // Special styling for session separators
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = text,
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        } else {
                            val isToolCall = text.startsWith("🔧 TOOL CALLED:")
                            
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = "${index + 1}. ${role.uppercase()}${if (isToolCall) " (TOOL)" else ""}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = text,
                                        maxLines = if (isToolCall) 3 else 10,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}


