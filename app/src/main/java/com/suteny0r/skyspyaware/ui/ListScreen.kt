package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.Drone
import com.suteny0r.skyspyaware.DroneClassifier
import com.suteny0r.skyspyaware.PilotRole
import com.suteny0r.skyspyaware.StatPoint
import com.suteny0r.skyspyaware.isValidPosition

@Composable
fun ListScreen(
    drones: List<Drone>,
    simulatorKeys: Set<String> = emptySet(),
    platformKeys: Set<String> = emptySet(),
    satellite: Map<String, Map<String, Int>> = emptyMap(),
    onSelect: (String) -> Unit,
    onReclassify: (String) -> Unit = {}
) {
    if (drones.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No drones detected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(drones, key = { it.key }) { d ->
            val (role, reason) = remember(d, satellite[d.key]) {
                val pts = d.trail.map { StatPoint(it.ts, it.lat, it.lon, d.droneAltitude) }
                DroneClassifier.classify(pts, satellite[d.key] ?: emptyMap())
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(d.key) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.basicId.ifBlank { d.mac },
                            fontWeight = FontWeight.Bold
                        )
                        if (d.basicId.isNotBlank() && d.basicId in simulatorKeys) {
                            Text(
                                " SIM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (d.basicId.isNotBlank() && d.basicId in platformKeys) {
                            Text(
                                " PS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Text(
                        "${d.mac}  •  alt ${d.droneAltitude}m  •  RSSI ${d.rssi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        roleLabel(role),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = roleColor(role)
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                    TextButton(
                        onClick = { onReclassify(d.key) },
                        enabled = isValidPosition(d.droneLat, d.droneLon)
                    ) {
                        Text("Reclassify", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    ageString(d.lastSeen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            HorizontalDivider()
        }
    }
}

private fun roleLabel(role: PilotRole): String = when (role) {
    PilotRole.PORT_INSPECTION -> "Ship / port inspection"
    PilotRole.BEACH_TOURISM -> "Beach tourism / recreational"
    PilotRole.SITE_MONITORING -> "Construction / site monitoring"
    PilotRole.GENERAL -> "General / transit"
    PilotRole.UNKNOWN -> "Unknown"
}

private fun roleColor(role: PilotRole): androidx.compose.ui.graphics.Color = when (role) {
    PilotRole.PORT_INSPECTION -> androidx.compose.ui.graphics.Color(0xFF00BCD4)
    PilotRole.BEACH_TOURISM -> androidx.compose.ui.graphics.Color(0xFF00C853)
    PilotRole.SITE_MONITORING -> androidx.compose.ui.graphics.Color(0xFFFF9800)
    PilotRole.GENERAL -> androidx.compose.ui.graphics.Color(0xFF2196F3)
    PilotRole.UNKNOWN -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
}

private fun ageString(lastSeen: Long): String {
    val s = (System.currentTimeMillis() - lastSeen) / 1000
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}
