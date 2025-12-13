package com.timelinter.app

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timelinter.app.ui.components.AppTopBar
import com.timelinter.app.ui.components.NavigationActions
import com.timelinter.app.ui.components.TopNavigationMenu
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.minutes

private enum class RateMode { DEPLETE, REFILL }

private data class EditableCategory(
    var id: String = "",
    var label: String = "",
    var rateText: String = "",
    var rateMode: RateMode = RateMode.DEPLETE,
    var freeMinutes: String = "",
    var freePeriods: String = "",
    var allowOverfill: Boolean = false,
    var emoji: String = ""
)

@Composable
fun AppCategoriesScreen(
    onNavigateBack: () -> Unit,
    navigationActions: NavigationActions,
    monitoringActive: Boolean? = null
) {
    val context = LocalContext.current
    val config = remember { AppCategoryConfigManager(context) }
    var categories by remember { mutableStateOf(config.getDisplayCategories()) }
    var assignments by remember { mutableStateOf(config.getAppAssignments()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf(listOf<AppInfo>()) }
    var expandedCategories = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun refresh() {
        val all = config.getDisplayCategories()
        val defaultCat = all.firstOrNull { it.id == AppCategoryIds.DEFAULT }
        val nonDefault = all.filterNot { it.id == AppCategoryIds.DEFAULT }
        categories = nonDefault + listOfNotNull(defaultCat)
        assignments = config.getAppAssignments()
    }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        try {
            val apps = pm.queryIntentActivities(mainIntent, 0).mapNotNull { resolveInfo ->
                try {
                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString()
                    )
                } catch (_: Exception) {
                    null
                }
            }.filter { it.packageName != context.packageName }
                .sortedBy { it.appName }
            installedApps = apps
        } catch (_: SecurityException) {
            showPermissionDialog = true
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Cannot Access Apps") },
            text = {
                Text(
                    "Time Linter cannot see your installed apps. This may be due to Android's package visibility restrictions."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                ) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "App Categories",
                monitoringActive = monitoringActive,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { TopNavigationMenu(navigationActions) }
            )
        },
        floatingActionButton = {
            CategoryCreationFab { newCat ->
                config.addOrUpdateCategory(newCat)
                refresh()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search apps") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val filteredAppsByCategory = categories.mapNotNull { cat ->
                    val appsForCat = installedApps
                        .filter { (assignments[it.packageName] ?: AppCategoryIds.DEFAULT) == cat.id }
                        .filter { searchQuery.isBlank() || it.appName.contains(searchQuery, ignoreCase = true) }
                    if (appsForCat.isNotEmpty()) cat to appsForCat else null
                }

                val autoExpand = searchQuery.isNotBlank() && filteredAppsByCategory.size <= 10

                items(filteredAppsByCategory, key = { it.first.id }) { (category, appsForCategory) ->
                    val availableApps = installedApps
                        .filter { (assignments[it.packageName] ?: AppCategoryIds.DEFAULT) != category.id }
                        .filter { searchQuery.isBlank() || it.appName.contains(searchQuery, ignoreCase = true) }
                    CategoryCard(
                        category = category,
                        isEditable = category.id !in listOf(AppCategoryIds.DEFAULT, AppCategoryIds.CUSTOM),
                        expanded = expandedCategories.contains(category.id) || autoExpand,
                        onToggleExpanded = {
                            if (expandedCategories.contains(category.id)) {
                                expandedCategories.remove(category.id)
                            } else {
                                expandedCategories.add(category.id)
                            }
                        },
                        onSave = { updated ->
                            config.addOrUpdateCategory(
                                CategoryParameters(
                                    id = updated.id,
                                    label = updated.label,
                                    minutesChangePerMinute = updated.minutesChangePerMinute,
                                    freeMinutesPerPeriod = updated.freeMinutesPerPeriod,
                                    freePeriodsPerDay = updated.freePeriodsPerDay,
                                    allowOverfill = updated.allowOverfill,
                                    usesNeutralTimers = updated.usesNeutralTimers,
                                    emoji = updated.emoji
                                )
                            )
                            refresh()
                        },
                        onCancel = {
                            expandedCategories.remove(category.id)
                        },
                        onRemove = {
                            config.removeCategory(category.id)
                            refresh()
                        },
                        apps = appsForCategory,
                        availableApps = availableApps,
                        allCategories = categories,
                        assignments = assignments,
                        onAssign = { app, targetId, customParams ->
                            scope.launch {
                                config.assignAppToCategory(app.packageName, targetId, customParams)
                                assignments = config.getAppAssignments()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCreationFab(onCreate: (CategoryParameters) -> Unit) {
    var show by remember { mutableStateOf(false) }
    FloatingActionButtonWithDialog(show = show, onShowChange = { show = it }, onCreate = onCreate)
}

@Composable
private fun FloatingActionButtonWithDialog(
    show: Boolean,
    onShowChange: (Boolean) -> Unit,
    onCreate: (CategoryParameters) -> Unit
) {
    FloatingActionButton(onClick = { onShowChange(true) }) {
        Icon(Icons.Default.Add, contentDescription = "Add category")
    }

    if (!show) return

    var state by remember {
        mutableStateOf(
            EditableCategory(
                id = "category_${System.currentTimeMillis().absoluteValue}",
                label = "",
                rateText = "1",
                rateMode = RateMode.REFILL,
                freeMinutes = "",
                freePeriods = "",
                allowOverfill = false,
                emoji = ""
            )
        )
    }

    AlertDialog(
        onDismissRequest = { onShowChange(false) },
        title = { Text("Add Category") },
        text = {
            CategoryEditor(state = state, onChange = { state = it }, allowIdEdit = true)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newCat = CategoryParameters(
                        id = state.id,
                        label = state.label.ifBlank { "Category" },
                        minutesChangePerMinute = signedRate(state),
                        freeMinutesPerPeriod = state.freeMinutes.toIntOrNull() ?: 0,
                        freePeriodsPerDay = state.freePeriods.toIntOrNull() ?: 0,
                        allowOverfill = state.allowOverfill,
                        usesNeutralTimers = false,
                        emoji = state.emoji
                    )
                    onCreate(newCat)
                    onShowChange(false)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onShowChange(false) }) { Text("Cancel") } }
    )
}

@Composable
private fun CategoryCard(
    category: ResolvedCategory,
    isEditable: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (ResolvedCategory) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    apps: List<AppInfo>,
    availableApps: List<AppInfo>,
    allCategories: List<ResolvedCategory>,
    assignments: Map<String, String>,
    onAssign: (AppInfo, String, CategoryParameters?) -> Unit
) {
    var editState by remember {
        mutableStateOf(
            EditableCategory(
                id = category.id,
                label = category.label,
                rateText = category.minutesChangePerMinute?.absoluteValue?.toString() ?: "1",
                rateMode = if ((category.minutesChangePerMinute ?: 0f) < 0f) RateMode.DEPLETE else RateMode.REFILL,
                freeMinutes = category.freeMinutesPerPeriod.toString(),
                freePeriods = category.freePeriodsPerDay.toString(),
                allowOverfill = category.allowOverfill,
                emoji = category.emoji
            )
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${category.label}${if (category.emoji.isNotBlank()) " ${category.emoji}" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isEditable) {
                        CategoryEditor(state = editState, onChange = { editState = it }, allowIdEdit = false)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val updated = category.copy(
                                    label = editState.label.ifBlank { category.label },
                                    minutesChangePerMinute = signedRate(editState),
                                    freeMinutesPerPeriod = editState.freeMinutes.toIntOrNull() ?: 0,
                                    freePeriodsPerDay = editState.freePeriods.toIntOrNull() ?: 0,
                                    allowOverfill = editState.allowOverfill,
                                    usesNeutralTimers = false,
                                    emoji = editState.emoji
                                )
                                onSave(updated)
                                onCancel()
                            }) { Text("Save") }
                            TextButton(onClick = {
                                editState = EditableCategory(
                                    id = category.id,
                                    label = category.label,
                                    rateText = category.minutesChangePerMinute?.absoluteValue?.toString() ?: "1",
                                    rateMode = if ((category.minutesChangePerMinute ?: 0f) < 0f) RateMode.DEPLETE else RateMode.REFILL,
                                    freeMinutes = category.freeMinutesPerPeriod.toString(),
                                    freePeriods = category.freePeriodsPerDay.toString(),
                                    allowOverfill = category.allowOverfill,
                                    emoji = category.emoji
                                )
                                onCancel()
                            }) { Text("Cancel") }
                            TextButton(onClick = onRemove) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    } else {
                        Text("Parameters are fixed for this category.")
                    }

                    Text(
                        text = "Apps (${apps.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    var expandedFor: String? by remember { mutableStateOf(null) }
                    if (apps.isEmpty()) {
                        Text("No apps assigned.")
                    } else {
                        apps.forEach { app ->
                            val currentCat = assignments[app.packageName] ?: AppCategoryIds.DEFAULT
                    AppAssignmentRow(
                        app = app,
                        categories = allCategories,
                        currentCategory = currentCat,
                        expanded = expandedFor == app.packageName,
                        onExpandedChange = { expandedFor = if (expandedFor == it) null else it },
                        onAssign = onAssign
                    )
                        }
                    }

                    if (availableApps.isNotEmpty()) {
                        var addExpanded by remember { mutableStateOf(false) }
                        Text(
                            "Assign another app to ${category.label}${if (category.emoji.isNotBlank()) " ${category.emoji}" else ""}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Box {
                            Button(onClick = { addExpanded = true }) { Text("Add app") }
                            DropdownMenu(expanded = addExpanded, onDismissRequest = { addExpanded = false }) {
                                availableApps.forEach { app ->
                                    DropdownMenuItem(
                                        text = { Text(app.appName) },
                                        onClick = {
                                            onAssign(app, category.id, null)
                                            addExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryEditor(state: EditableCategory, onChange: (EditableCategory) -> Unit, allowIdEdit: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowIdEdit) {
            OutlinedTextField(
                value = state.id,
                onValueChange = { onChange(state.copy(id = it)) },
                label = { Text("Category ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.label,
                onValueChange = { onChange(state.copy(label = it)) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.weight(4f)
            )
            OutlinedTextField(
                value = state.emoji,
                onValueChange = { onChange(state.copy(emoji = it.take(4))) },
                label = { Text("Emoji") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.rateMode == RateMode.DEPLETE,
                    onClick = { onChange(state.copy(rateMode = RateMode.DEPLETE, allowOverfill = false)) }
                )
                Text("Deplete")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.rateMode == RateMode.REFILL,
                    onClick = { onChange(state.copy(rateMode = RateMode.REFILL)) }
                )
                Text("Refill")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.allowOverfill && state.rateMode == RateMode.REFILL,
                    onCheckedChange = { onChange(state.copy(allowOverfill = it && state.rateMode == RateMode.REFILL)) },
                    enabled = state.rateMode == RateMode.REFILL
                )
                Text("Allow overfill")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sign = if (state.rateMode == RateMode.REFILL) "+" else "-"
            Text(sign, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.rateText,
                onValueChange = { onChange(state.copy(rateText = it.filter { ch -> ch.isDigit() || ch == '.' })) },
                isError = state.rateText.contains('-'),
                label = { Text("Rate magnitude (minutes per minute)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.freeMinutes,
                onValueChange = { onChange(state.copy(freeMinutes = it.filter { ch -> ch.isDigit() })) },
                label = { Text("Free minutes per period") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.freePeriods,
                onValueChange = { onChange(state.copy(freePeriods = it.filter { ch -> ch.isDigit() })) },
                label = { Text("Free periods per day") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun signedRate(state: EditableCategory): Float? {
    val magnitude = state.rateText.toFloatOrNull()?.absoluteValue ?: return null
    return if (state.rateMode == RateMode.DEPLETE) -magnitude else magnitude
}

@Composable
private fun AppAssignmentRow(
    app: AppInfo,
    categories: List<ResolvedCategory>,
    currentCategory: String,
    expanded: Boolean,
    onExpandedChange: (String) -> Unit,
    onAssign: (AppInfo, String, CategoryParameters?) -> Unit
) {
    var showCustomEditor by remember { mutableStateOf(currentCategory == AppCategoryIds.CUSTOM) }
    var customState by remember {
        mutableStateOf(
            EditableCategory(
                id = AppCategoryIds.CUSTOM,
                label = "Custom",
                rateText = "",
                rateMode = RateMode.REFILL,
                freeMinutes = "0",
                freePeriods = "0",
                allowOverfill = false,
                emoji = "🛠"
            )
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(app.packageName) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(app.appName, modifier = Modifier.weight(1f))
            Text(
                "Move",
                style = MaterialTheme.typography.bodySmall
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange("") }) {
            categories.forEach { cat ->
                DropdownMenuItem(
                        text = { Text("to ${cat.label}${if (cat.emoji.isNotBlank()) " ${cat.emoji}" else ""}") },
                    onClick = {
                        if (cat.id == AppCategoryIds.CUSTOM) {
                            val params = CategoryParameters(
                                id = AppCategoryIds.CUSTOM,
                                label = "Custom",
                                minutesChangePerMinute = signedRate(customState),
                                freeMinutesPerPeriod = customState.freeMinutes.toIntOrNull() ?: 0,
                                freePeriodsPerDay = customState.freePeriods.toIntOrNull() ?: 0,
                                allowOverfill = customState.allowOverfill,
                                usesNeutralTimers = false,
                                emoji = customState.emoji
                            )
                            onAssign(app, cat.id, params)
                            showCustomEditor = true
                        } else {
                            onAssign(app, cat.id, null)
                            showCustomEditor = false
                        }
                        onExpandedChange("")
                    }
                )
            }
        }

        AnimatedVisibility(visible = showCustomEditor) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Custom parameters for ${app.appName}", style = MaterialTheme.typography.titleSmall)
                CategoryEditor(state = customState, onChange = { customState = it }, allowIdEdit = false)
                Button(onClick = {
                    val params = CategoryParameters(
                        id = AppCategoryIds.CUSTOM,
                        label = "Custom",
                        minutesChangePerMinute = signedRate(customState),
                        freeMinutesPerPeriod = customState.freeMinutes.toIntOrNull() ?: 0,
                        freePeriodsPerDay = customState.freePeriods.toIntOrNull() ?: 0,
                        allowOverfill = customState.allowOverfill,
                        usesNeutralTimers = false,
                        emoji = customState.emoji
                    )
                    onAssign(app, AppCategoryIds.CUSTOM, params)
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save Custom")
                }
            }
        }
    }
}

