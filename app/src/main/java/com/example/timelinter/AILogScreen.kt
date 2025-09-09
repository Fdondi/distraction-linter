package com.example.timelinter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = "${index + 1}. ${role.uppercase()}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = text,
                                        maxLines = 10,
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


