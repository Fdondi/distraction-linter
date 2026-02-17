package com.timelinter.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.io.File

@Composable
fun AIConfigScreen() {
    val context = LocalContext.current
    var taskConfigs by remember { mutableStateOf(AIConfigManager.getAllTaskConfigurations(context)) }
    val availableModels = remember { AIConfigManager.getAvailableModels() }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf("") }
    var importJson by remember { mutableStateOf("") }

    // On-device model paths
    var mediaPipeModelPath by remember {
        mutableStateOf(SettingsManager.getMediaPipeModelPath(context) ?: "")
    }
    var liteRtModelPath by remember {
        mutableStateOf(SettingsManager.getLiteRtModelPath(context) ?: "")
    }
    // On-device model file availability for greying out picker options
    val onDeviceModelAvailability = remember(mediaPipeModelPath, liteRtModelPath) {
        mapOf(
            AIModelId.ON_DEVICE_MEDIAPIPE to (mediaPipeModelPath.isNotBlank() && File(mediaPipeModelPath).exists()),
            AIModelId.ON_DEVICE_LITERT to (liteRtModelPath.isNotBlank() && File(liteRtModelPath).exists()),
        )
    }

    // Refresh configs helper
    val refreshConfigs = {
        taskConfigs = AIConfigManager.getAllTaskConfigurations(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "AI Configuration",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Configure which AI model to use for each task",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Task configurations
        AITask.entries.forEach { task ->
            TaskConfigurationCard(
                task = task,
                currentConfig = taskConfigs[task]!!,
                availableModels = availableModels,
                onDeviceModelAvailability = onDeviceModelAvailability,
                onModelSelected = { newModel ->
                    AIConfigManager.setModelForTask(context, task, newModel)
                    refreshConfigs()
                    Toast.makeText(
                        context,
                        "Updated ${task.displayName} to ${newModel.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // On-device model path configurations (always visible so users can configure paths
        // before selecting an on-device model in the picker)
        Text(
            text = "On-Device Models",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        OnDeviceModelPathCard(
            title = "MediaPipe Model (.task)",
            description = "Download from HuggingFace (litert-community), then push via:\nadb push model.task /data/local/tmp/llm/",
            modelPath = mediaPipeModelPath,
            placeholder = "/data/local/tmp/llm/model.task",
            testTag = "mediapipe_model_path",
            onPathChanged = { newPath ->
                mediaPipeModelPath = newPath
                SettingsManager.setMediaPipeModelPath(
                    context,
                    newPath.takeIf { it.isNotBlank() }
                )
            },
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OnDeviceModelPathCard(
            title = "LiteRT-LM Model (.litertlm)",
            description = "Download gemma-3n-E2B-it-int4.litertlm from HuggingFace (google/gemma-3n-E2B-it-litert-lm), then push via:\nadb push model.litertlm /data/local/tmp/llm/",
            modelPath = liteRtModelPath,
            placeholder = "/data/local/tmp/llm/gemma-3n-E2B-it-int4.litertlm",
            testTag = "litert_model_path",
            onPathChanged = { newPath ->
                liteRtModelPath = newPath
                SettingsManager.setLiteRtModelPath(
                    context,
                    newPath.takeIf { it.isNotBlank() }
                )
            },
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    AIConfigManager.resetToDefaults(context)
                    refreshConfigs()
                    Toast.makeText(context, "Reset to default configuration", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset to Defaults")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    exportedJson = AIConfigManager.exportConfiguration(context)
                    showExportDialog = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Config")
            }

            OutlinedButton(
                onClick = {
                    importJson = ""
                    showImportDialog = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Config")
            }
        }
    }

    // Export dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Configuration") },
            text = {
                Column {
                    Text("Copy this JSON configuration:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = exportedJson,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Copy to clipboard
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
                        as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("AI Config", exportedJson)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    showExportDialog = false
                }) {
                    Text("Copy to Clipboard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Import dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Configuration") },
            text = {
                Column {
                    Text("Paste JSON configuration:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val success = AIConfigManager.importConfiguration(context, importJson)
                        if (success) {
                            refreshConfigs()
                            Toast.makeText(context, "Configuration imported", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        } else {
                            Toast.makeText(context, "Invalid JSON", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = importJson.isNotBlank()
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TaskConfigurationCard(
    task: AITask,
    currentConfig: AIModelConfig,
    availableModels: List<AIModelConfig>,
    onDeviceModelAvailability: Map<AIModelId, Boolean>,
    onModelSelected: (AIModelConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    // Helper to format price
    fun formatPrice(config: AIModelConfig): String {
        if (config.inputCost == 0 && config.outputCost == 0) return "Free (on-device)"
        val inputPrice = config.inputCost / 100.0
        val outputPrice = config.outputCost / 100.0
        return "In: $${String.format("%.2f", inputPrice)}/M • Out: $${String.format("%.2f", outputPrice)}/M"
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Model selector (stable DropdownMenu)
            OutlinedTextField(
                value = currentConfig.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_selector_${task.name}")
                    .clickable { expanded = !expanded }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableModels.forEach { model ->
                    val isOnDevice = model.provider.isOnDevice
                    val fileAvailable = onDeviceModelAvailability[model.id] == true
                    val showWarning = isOnDevice && !fileAvailable

                    DropdownMenuItem(
                        modifier = Modifier.testTag("model_option_${task.name}_${model.id.name}"),
                        text = {
                            Column {
                                Text(model.displayName)
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (showWarning) {
                                    Text(
                                        text = "⚠ Model file not found — configure path below",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = formatPrice(model),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }
            }

            // Current model description and price
            if (currentConfig.description.isNotEmpty()) {
                Text(
                    text = currentConfig.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("model_description_${task.name}")
                )
            }
            Text(
                text = formatPrice(currentConfig),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun OnDeviceModelPathCard(
    title: String,
    description: String,
    modelPath: String,
    placeholder: String,
    testTag: String,
    onPathChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val modelFileExists = remember(modelPath) {
        modelPath.isNotBlank() && File(modelPath).exists()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            OutlinedTextField(
                value = modelPath,
                onValueChange = onPathChanged,
                label = { Text("Model file path") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
            )

            // Status indicator
            val statusText: String
            val statusColor: androidx.compose.ui.graphics.Color
            when {
                modelPath.isBlank() -> {
                    statusText = "No path configured"
                    statusColor = MaterialTheme.colorScheme.error
                }
                modelFileExists -> {
                    statusText = "Model file found"
                    statusColor = MaterialTheme.colorScheme.primary
                }
                else -> {
                    statusText = "Model file not found at this path"
                    statusColor = MaterialTheme.colorScheme.error
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
