package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.DataRepo
import com.suteny0r.skyspyaware.FlightSummary
import com.suteny0r.skyspyaware.SkySpyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppRoot(vm: SkySpyViewModel) {
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val drones by vm.drones.collectAsState()
    val console by vm.console.collectAsState()
    val connected by vm.connected.collectAsState()
    val faa by vm.faa.collectAsState()
    val faaPlatform by vm.faaPlatform.collectAsState()
    val satellite by vm.satellite.collectAsState()
    val notes by vm.droneNotes.collectAsState()

    var mapCamera by remember { mutableStateOf<MapCamera?>(null) }
    var mapStyle by remember { mutableStateOf(vm.getMapStyle().coerceIn(0, MAP_STYLES.lastIndex)) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    var flightMapKey by remember { mutableStateOf<String?>(null) }
    var selectedFlight by remember { mutableStateOf<FlightSummary?>(null) }

    // Hoisted so the Drone models tree keeps its expand state while the
    // flight map trail (which replaces the tab content) is shown.
    var expandedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedDrones by remember { mutableStateOf<Set<String>>(emptySet()) }

    val selectedDrone = drones.firstOrNull { it.key == selectedKey }

    // Drone keys whose basicId failed the FAA registration lookup: treated as
    // simulator/test drones. Uses the same test as the stats exclusion
    // (DataRepo.isSimulator), which also accepts legacy cached sentinel texts;
    // a strict FAA_NOT_FOUND equality here made the SIM badge disagree with
    // the "excluded from statistics" set for pre-fix cache rows.
    val simulatorKeys = remember(faa) {
        faa.filterValues { DataRepo.isSimulator(it) }.keys.toSet()
    }

    // Drone keys on a public-safety-style platform (heuristic from make/model).
    val platformKeys = remember(faaPlatform) {
        faaPlatform.filterValues { it.isNotBlank() }.keys.toSet()
    }

    val historyMinutes by vm.historyMinutes.collectAsState()
    val historyScale by vm.historyScale.collectAsState()
    val historyMaxMinutes = vm.historyMaxMinutes()
    // Window filter: show only drones seen within the history window and clip
    // their trails to that window. 0 = live only (~1 min). Values up to 1 week
    // show [now - value, now]; past 1 week the slider positions a fixed 1-week
    // window that slides continuously back in time instead of ever-longer spans.
    // Re-evaluate the window over time: with a quiet feed nothing else
    // invalidates it, so a "live only" (60s) view would keep showing a drone
    // last seen an hour ago.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            nowTick = System.currentTimeMillis()
        }
    }
    val nowMs = nowTick
    val weekMs = 7L * 24 * 60 * 60 * 1000
    val windowStartMs = if (historyMinutes <= 0) nowMs - 60_000L
    else nowMs - historyMinutes * 60_000L
    val windowEndMs = minOf(nowMs, windowStartMs + weekMs)
    val visibleDrones = remember(drones, historyMinutes, historyScale, nowTick) {
        drones
            // A drone belongs to the window if it was seen inside it OR has
            // trail points inside it: a daily flyer must still appear in a
            // past week's window even though its lastSeen is newer.
            .filter { d ->
                (d.lastSeen in windowStartMs..windowEndMs) ||
                    d.trail.any { p -> p.ts in windowStartMs..windowEndMs }
            }
            .map {
                it.copy(trail = it.trail.filter { p -> p.ts >= windowStartMs && p.ts <= windowEndMs })
            }
    }

    // Selecting a drone (map marker / path tap, list tap, or notification).
    // Map and list taps show the detail on the List tab; a notification tap
    // opens the Map tab centered on the drone instead.
    fun select(key: String, openMap: Boolean = false) {
        selectedKey = key
        focusKey = key
        focusTick++
        tab = if (openMap) 0 else 1
    }

    // When a new-drone notification is tapped, open the map centered on that
    // drone even if it is no longer actively broadcasting.
    val pendingSelection by vm.pendingSelection.collectAsState()
    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { key ->
            // A flight-replay screen replaces the whole tab UI; close it or
            // the selection is consumed with no visible response.
            selectedFlight = null
            flightMapKey = null
            select(key, openMap = true)
            vm.consumePendingSelection()
        }
    }

    val flightsKey = flightMapKey
    val openFlight = selectedFlight
    when {
        openFlight != null -> DroneFlightsScreen(
            vm,
            openFlight.droneKey,
            onBack = { selectedFlight = null },
            window = openFlight.startTs to openFlight.endTs
        )
        flightsKey != null -> DroneFlightsScreen(vm, flightsKey, onBack = { flightMapKey = null })
        else -> Scaffold(
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
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Filled.BarChart, contentDescription = "Statistics") },
                    label = { Text("Stats") }
                )
                NavigationBarItem(
                    selected = tab == 5,
                    onClick = { tab = 5 },
                    icon = { Icon(Icons.Filled.Category, contentDescription = "Drone inventory") },
                    label = { Text("Drones") }
                )
                NavigationBarItem(
                    selected = tab == 6,
                    onClick = { tab = 6 },
                    icon = { Icon(Icons.Filled.Flight, contentDescription = "Flights") },
                    label = { Text("Flights") }
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
                    onDroneSelected = { key -> select(key) },
                    focusKey = focusKey,
                    focusTick = focusTick,
                    onFocusConsumed = { focusKey = null },
                    historyMinutes = historyMinutes,
                    onHistoryChange = { vm.setHistoryMinutes(it) },
                    historyMaxMinutes = historyMaxMinutes,
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> {
                    val list = visibleDrones
                    val listIndex = list.indexOfFirst { it.key == selectedKey }
                    when {
                        // Detail for a drone in the current window: swipe up/down
                        // to move through the list.
                        listIndex >= 0 -> {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    TextButton(onClick = { selectedKey = null }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to list"
                                        )
                                        Text("List", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                                val pagerState =
                                    rememberPagerState(initialPage = listIndex) { list.size }
                                // The pager is positional over a live-filtered
                                // list, so re-sync it whenever EITHER the
                                // selection or the list membership changes; on
                                // churn (a drone aging out shifts the indices)
                                // the page under the finger would otherwise
                                // silently become a different drone.
                                LaunchedEffect(selectedKey, list) {
                                    val i = list.indexOfFirst { it.key == selectedKey }
                                    if (i >= 0 && i != pagerState.settledPage) {
                                        pagerState.scrollToPage(i)
                                    }
                                }
                                // Swiping up/down moves through the list. Only
                                // adopt the settled page while the selected
                                // drone is still in the list: when it just left
                                // the window this fires from index shift, and
                                // adopting would rewrite the selection to an
                                // arbitrary neighbor.
                                LaunchedEffect(pagerState.settledPage) {
                                    if (list.any { it.key == selectedKey }) {
                                        list.getOrNull(pagerState.settledPage)?.let {
                                            selectedKey = it.key
                                        }
                                    }
                                }
                                VerticalPager(
                                    state = pagerState,
                                    key = { list[it].key },
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    val dk = list[page].basicId.ifBlank { list[page].mac }
                                    DroneDetail(
                                        list[page],
                                        faa[list[page].basicId],
                                        faaPlatform[list[page].basicId],
                                        note = notes[dk] ?: "",
                                        onNoteChange = { vm.setDroneNote(dk, it) },
                                        onShowFlights = { flightMapKey = list[page].key }
                                    )
                                }
                            }
                        }
                        // Selected drone exists but is outside the history
                        // window: show it alone, no pager.
                        selectedDrone != null -> {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    TextButton(onClick = { selectedKey = null }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to list"
                                        )
                                        Text("List", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                                val dk = selectedDrone.basicId.ifBlank { selectedDrone.mac }
                                DroneDetail(
                                    selectedDrone,
                                    faa[selectedDrone.basicId],
                                    faaPlatform[selectedDrone.basicId],
                                    note = notes[dk] ?: "",
                                    onNoteChange = { vm.setDroneNote(dk, it) },
                                    onShowFlights = { flightMapKey = selectedDrone.key }
                                )
                            }
                        }
                        else -> ListScreen(
                            visibleDrones,
                            simulatorKeys = simulatorKeys,
                            platformKeys = platformKeys,
                            satellite = satellite,
                            notes = notes,
                            onSelect = { key -> select(key) },
                            onReclassify = { key ->
                                scope.launch {
                                    val counts = vm.reclassify(key)
                                    if (counts != null) {
                                        vm.refreshStats()
                                    }
                                }
                            }
                        )
                    }
                }
                2 -> ConsoleScreen(console)
                3 -> SettingsScreen(vm)
                4 -> StatsScreen(vm, onSelect = { key -> select(key) })
                5 -> DroneStatsScreen(
                    vm,
                    onSelect = { key -> select(key) },
                    onShowFlight = { selectedFlight = it },
                    onShowDroneTrail = { flightMapKey = it },
                    notes = notes,
                    expandedModels = expandedModels,
                    onModelToggle = { model ->
                        expandedModels = if (model in expandedModels) expandedModels - model else expandedModels + model
                    },
                    expandedDrones = expandedDrones,
                    onDroneToggle = { key ->
                        expandedDrones = if (key in expandedDrones) expandedDrones - key else expandedDrones + key
                    }
                )
                6 -> FlightsScreen(vm, onSelectFlight = { selectedFlight = it })
            }
        }
    }
    }
}
