package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val faa by vm.faa.collectAsState()

    var mapCamera by remember { mutableStateOf<MapCamera?>(null) }
    var mapStyle by remember { mutableStateOf(vm.getMapStyle().coerceIn(0, MAP_STYLES.lastIndex)) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableStateOf(0) }

    val selectedDrone = drones.firstOrNull { it.key == selectedKey }

    val historyMinutes by vm.historyMinutes.collectAsState()
    // Window filter: show only drones seen within the history window and clip
    // their trails to that window. 0 = live only (~1 min).
    val cutoffMs = if (historyMinutes <= 0) 60_000L else historyMinutes * 60_000L
    val cutoff = System.currentTimeMillis() - cutoffMs
    val visibleDrones = remember(drones, historyMinutes) {
        drones
            .filter { it.lastSeen >= cutoff }
            .map { it.copy(trail = it.trail.filter { p -> p.ts >= cutoff }) }
    }

    fun select(key: String, jumpToMap: Boolean) {
        selectedKey = key
        if (jumpToMap) {
            focusKey = key
            focusTick++
            tab = 0
        }
    }

    // When a new-drone notification is tapped, jump to that drone even if it
    // is no longer actively broadcasting.
    val pendingSelection by vm.pendingSelection.collectAsState()
    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { key ->
            select(key, jumpToMap = true)
            vm.consumePendingSelection()
        }
    }

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
                    icon = { Icon(Icons.Filled.List, contentDescription = "List") },
                    label = { Text("List") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = "Console") },
                    label = { Text("Console") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> MapScreen(
                    savedCamera = mapCamera,
                    onCameraChange = { mapCamera = it },
                    drones = visibleDrones,
                    allDrones = drones,
                    mapStyle = mapStyle,
                    onStyleChange = {
                        mapStyle = it
                        vm.setMapStyle(it)
                    },
                    onDroneSelected = { key -> select(key, jumpToMap = false) },
                    focusKey = focusKey,
                    focusTick = focusTick,
                    historyMinutes = historyMinutes,
                    onHistoryChange = { vm.setHistoryMinutes(it) },
                    modifier = Modifier.fillMaxSize()
                )
                1 -> ListScreen(visibleDrones, onSelect = { key -> select(key, jumpToMap = true) })
                2 -> ConsoleScreen(console)
                3 -> SettingsScreen(vm)
            }
        }
    }

    if (selectedDrone != null) {
        ModalBottomSheet(onDismissRequest = { selectedKey = null }) {
            DroneDetail(selectedDrone, faa[selectedDrone.basicId])
        }
    }
}
