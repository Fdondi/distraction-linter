package com.timelinter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class NavigationActions(
    val onGoHome: () -> Unit,
    val onOpenCategories: () -> Unit,
    val onOpenTimers: () -> Unit,
    val onOpenLog: () -> Unit,
    val onOpenAIConfig: () -> Unit
)

@Composable
fun AppTopBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    monitoringActive: Boolean? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(48.dp).fillMaxHeight()) {
                    navigationIcon?.let { icon ->
                        Row(
                            modifier = Modifier.fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            icon()
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        monitoringActive?.let { isActive ->
                            val tint =
                                if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            val statusDescription =
                                if (isActive) "Monitoring active" else "Monitoring stopped"
                            Icon(
                                imageVector = Icons.Default.Hexagon,
                                contentDescription = statusDescription,
                                tint = tint
                            )
                        }
                        Text(text = title, style = MaterialTheme.typography.titleLarge)
                    }
                }

                // Reserve space symmetrical to navigation area
                Box(modifier = Modifier.width(48.dp))
            }

            actions?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = it
                )
            }
        }
    }
}

@Composable
fun TopNavigationMenu(actions: NavigationActions) {
    IconButton(onClick = actions.onGoHome) {
        Icon(Icons.Default.Home, contentDescription = "Home")
    }
    IconButton(onClick = actions.onOpenCategories) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "App Categories")
    }
    IconButton(onClick = actions.onOpenTimers) {
        Icon(Icons.Default.Timer, contentDescription = "Timer Settings")
    }
    IconButton(onClick = actions.onOpenLog) {
        Icon(Icons.Default.History, contentDescription = "AI Log")
    }
    IconButton(onClick = actions.onOpenAIConfig) {
        Icon(Icons.Default.Cloud, contentDescription = "AI Configuration")
    }
}


