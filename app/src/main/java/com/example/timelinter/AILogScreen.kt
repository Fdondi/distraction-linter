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
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart

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
            Text(
                text = "AI Memory",
                style = MaterialTheme.typography.titleMedium
            )
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
                    itemsIndexed(history) { index: Int, content: Content ->
                        val role = content.role ?: "unknown"
                        val text = content.parts.joinToString("\n") { part ->
                            when (part) {
                                is TextPart -> part.text
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


