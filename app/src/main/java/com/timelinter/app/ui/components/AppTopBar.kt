package com.timelinter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hexagon
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
    val onOpenApps: () -> Unit,
    val onOpenGoodApps: () -> Unit,
    val onOpenTimers: () -> Unit,
    val onOpenLog: () -> Unit,
    val onOpenAIConfig: () -> Unit
)

@Composable
fun AppTopBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(tonalElevation = 2.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(48.dp)) {
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
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                }

                Row(
                    modifier = Modifier.widthIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    content = { actions?.invoke(this) }
                )
            }
        }
    }
}

@Composable
fun TopNavigationMenu(actions: NavigationActions) {
    IconButton(onClick = actions.onOpenApps) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Wasteful Apps")
    }
    IconButton(onClick = actions.onOpenGoodApps) {
        Icon(Icons.Default.Hexagon, contentDescription = "Good Apps")
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


