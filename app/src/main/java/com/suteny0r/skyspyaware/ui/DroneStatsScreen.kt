package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.DroneCategory
import com.suteny0r.skyspyaware.DroneCatalog
import com.suteny0r.skyspyaware.FlightSummary
import com.suteny0r.skyspyaware.PilotProfile
import com.suteny0r.skyspyaware.SkySpyViewModel
import com.suteny0r.skyspyaware.Statistics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CATEGORY_LABELS = DroneCategory.entries.associateWith { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }

private val FLIGHT_DATE = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/** Dedicated tab: drone hardware inventory and likely-operator analytics. */
@Composable
fun DroneStatsScreen(
    vm: SkySpyViewModel,
    onSelect: (String) -> Unit,
    onShowFlight: (FlightSummary) -> Unit,
    onShowDroneTrail: (String) -> Unit,
    notes: Map<String, String> = emptyMap(),
    expandedModels: Set<String>,
    onModelToggle: (String) -> Unit,
    expandedDrones: Set<String>,
    onDroneToggle: (String) -> Unit
) {
    val stats by vm.stats.collectAsState()
    val flights by vm.flights.collectAsState()
    val suggestions by vm.noteSuggestions.collectAsState()
    var noteDialogKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshStats()
    }

    val s = stats
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Computing statistics...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    val flightsByDrone = remember(flights) { flights.groupBy { it.droneKey } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle("Drone fleet")
        if (s.modelCounts.isEmpty() && s.makeCounts.isEmpty()) {
            Text(
                "No make/model data yet - registration lookups are running in the background " +
                    "(the on-device database already holds a large historical lookup cache).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Makes", "${s.makeCounts.size}", Modifier.weight(1f))
            StatCell("Models", "${s.modelCounts.size}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Est. fleet value", formatCurrency(s.fleetValueUsd.toInt()), Modifier.weight(1f))
            // identifiedCount counts each drone once; summing the per-profile
            // chart would double-count dual-badged (industrial+public-safety)
            // aircraft.
            StatCell(
                "Identified",
                "${s.identifiedCount}",
                Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Drone makes")
        if (s.makeCounts.isEmpty()) {
            Text("No make data yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            val maxMake = s.makeCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            s.makeCounts.entries
                .sortedByDescending { it.value }
                .forEach { (make, count) -> MakeRow(make, count, maxMake) }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Drone models")
        if (s.modelCounts.isEmpty()) {
            Text("No model data yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            val maxModel = s.modelCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            s.modelCounts.entries
                .sortedByDescending { it.value }
                .forEach { (model, count) ->
                    ModelNode(
                        model = model,
                        count = count,
                        max = maxModel,
                        droneKeys = s.modelDrones[model] ?: emptyList(),
                        flightsByDrone = flightsByDrone,
                        onShowFlight = onShowFlight,
                        expanded = model in expandedModels,
                        onToggle = { onModelToggle(model) },
                        expandedDrones = expandedDrones,
                        onDroneToggle = onDroneToggle,
                        onShowDroneTrail = onShowDroneTrail,
                        onLongPress = { noteDialogKey = it },
                        notes = notes
                    )
                }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Likely pilot profiles")
        if (s.pilotProfileCounts.isEmpty()) {
            Text("No model attribution yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            val maxP = s.pilotProfileCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            PilotProfile.entries.forEach { p ->
                val count = s.pilotProfileCounts[p] ?: 0
                if (count > 0) PilotProfileRow(p, count, maxP)
            }
            Text(
                "Profiles inferred from aircraft class (MSRP + category). Cheap trainers " +
                    "suggest hobbyists; cinema and industrial platforms suggest paid operators.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Aircraft classes")
        if (s.categoryCounts.isEmpty()) {
            Text("No class data yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            val maxC = s.categoryCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            DroneCategory.entries.forEach { c ->
                val count = s.categoryCounts[c] ?: 0
                if (count > 0) CategoryRow(c, count, maxC)
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Per-drone hardware")
        if (s.topDrones.isEmpty()) {
            Text("No position-tracked drones yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        } else {
            val maxFlights = s.topDrones.maxOf { it.flights }.coerceAtLeast(1)
            s.topDrones.forEach { DroneRow(it, maxFlights, onClick = { onSelect(it.key) }) }
        }
    }

    noteDialogKey?.let { key ->
        NoteDialog(
            droneKey = key,
            currentNote = notes[key] ?: "",
            suggestions = suggestions,
            onSave = { vm.setDroneNote(key, it); noteDialogKey = null },
            onDismiss = { noteDialogKey = null }
        )
    }
}

@Composable
private fun NoteDialog(
    droneKey: String,
    currentNote: String,
    suggestions: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentNote) { mutableStateOf(currentNote) }
    val title = if (droneKey.length > 18) droneKey.take(18) + "…" else droneKey
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for $title") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Recent values",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    suggestions.forEach { s ->
                        TextButton(
                            onClick = { text = s },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                s,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MakeRow(make: String, count: Int, max: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(make, modifier = Modifier.width(180.dp), style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.weight(1f).height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(count.toFloat() / max).height(12.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            ) {}
        }
        Text("$count", modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ModelNode(
    model: String,
    count: Int,
    max: Int,
    droneKeys: List<String>,
    flightsByDrone: Map<String, List<FlightSummary>>,
    onShowFlight: (FlightSummary) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedDrones: Set<String>,
    onDroneToggle: (String) -> Unit,
    onShowDroneTrail: (String) -> Unit,
    onLongPress: (String) -> Unit,
    notes: Map<String, String>
) {
    val msrp = DroneCatalog.msrpForLabel(model)
    val label = if (msrp > 0) "$model  ${formatCurrency(msrp)}" else model
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.width(72.dp).height(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(count.toFloat() / max).height(10.dp)
                        .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                ) {}
            }
            Text("$count", modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            if (droneKeys.isEmpty()) {
                Text("  No drones attributed yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp))
            } else {
                droneKeys.forEach { key ->
                    DroneNode(
                        key,
                        flightsByDrone[key] ?: emptyList(),
                        onShowFlight,
                        expanded = key in expandedDrones,
                        onToggle = { onDroneToggle(key) },
                        onShowDroneTrail = { onShowDroneTrail(key) },
                        onLongPress = { onLongPress(key) },
                        note = notes[key] ?: ""
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DroneNode(
    key: String,
    flights: List<FlightSummary>,
    onShowFlight: (FlightSummary) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShowDroneTrail: (String) -> Unit,
    onLongPress: (String) -> Unit,
    note: String = ""
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 1.dp, bottom = 1.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = { onLongPress(key) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (expanded) "\u25BE" else "\u25B8",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            val idLabel = if (note.isNotBlank()) "$key  ($note)" else key
            Text(idLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (note.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface)
            Text("${flights.size}", modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            if (flights.isEmpty()) {
                Text("    No flights recorded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = { onShowDroneTrail(key) })
                        .padding(start = 40.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "All flights (${flights.size})",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("map", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                flights.forEach { f ->
                    FlightNode(f, onClick = { onShowFlight(f) })
                }
            }
        }
    }
}

@Composable
private fun FlightNode(f: FlightSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 40.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${FLIGHT_DATE.format(Date(f.startTs))}  •  ${formatDuration(f.durationMs)}  •  " +
                "${formatDistance(f.distanceM)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("map", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun PilotProfileRow(p: PilotProfile, count: Int, max: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, modifier = Modifier.width(200.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.weight(1f).height(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(count.toFloat() / max).height(12.dp)
                        .background(profileColor(p), RoundedCornerShape(4.dp))
                ) {}
            }
            Text("$count", modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium)
        }
        Text(p.description, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.8f))
    }
}

@Composable
private fun CategoryRow(c: DroneCategory, count: Int, max: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(CATEGORY_LABELS[c] ?: c.name, modifier = Modifier.width(180.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.weight(1f).height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(count.toFloat() / max).height(12.dp)
                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
            ) {}
        }
        Text("$count", modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium)
    }
}

fun profileColor(p: PilotProfile) = when (p) {
    PilotProfile.RECREATIONAL -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    PilotProfile.ENTHUSIAST -> androidx.compose.ui.graphics.Color(0xFF2196F3)
    PilotProfile.CINEMATOGRAPHY -> androidx.compose.ui.graphics.Color(0xFF9C27B0)
    PilotProfile.COMMERCIAL -> androidx.compose.ui.graphics.Color(0xFFFF9800)
    PilotProfile.PUBLIC_SAFETY -> androidx.compose.ui.graphics.Color(0xFFF44336)
    PilotProfile.UNKNOWN -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
}
