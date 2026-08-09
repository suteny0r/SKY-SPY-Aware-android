package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.suteny0r.skyspyaware.SkySpyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: SkySpyViewModel) {
    var tab by remember { mutableStateOf(0) }
    val drones by vm.drones.collectAsState()
    val console by vm.console.collectAsState()
    val connected by vm.connected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SKY-SPY-Aware") },
                actions = {
                    Text(
                        text = if (connected) "\u25CF Live" else "\u25CB Offline",
                        color = if (connected) Color(0xFF00C853) else Color(0xFFB71C1C)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Map, contentDescription = "Map") },
                    label = { Text("Map") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = "Console") },
                    label = { Text("Console") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> MapScreen(drones, Modifier.fillMaxSize())
                1 -> ConsoleScreen(console)
                2 -> SettingsScreen(vm)
            }
        }
    }
}
