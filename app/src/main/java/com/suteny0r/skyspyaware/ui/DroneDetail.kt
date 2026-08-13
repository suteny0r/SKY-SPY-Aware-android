package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.Drone
import com.suteny0r.skyspyaware.FAA_NOT_FOUND
import com.suteny0r.skyspyaware.isValidPosition
import java.util.Locale

@Composable
fun DroneDetail(
    d: Drone,
    faa: String?,
    platform: String? = null,
    onShowFlights: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Text(
            d.basicId.ifBlank { d.mac },
            style = MaterialTheme.typography.titleLarge
        )
        if (faa == FAA_NOT_FOUND) {
            Text(
                "Simulator / unregistered",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (!platform.isNullOrBlank()) {
            Text(
                platform,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Spacer(Modifier.height(12.dp))

        DetailRow("MAC", d.mac)
        DetailRow("Last seen", formatTime(d.lastSeen))
        DetailRow("RSSI", "${d.rssi} dBm")
        DetailRow("Altitude", "${d.droneAltitude} m (${(d.droneAltitude * 3.28084).toInt()} ft)")
        DetailRow(
            "Drone",
            String.format(Locale.US, "%.6f, %.6f", d.droneLat, d.droneLon)
        )
        if (isValidPosition(d.pilotLat, d.pilotLon)) {
            DetailRow(
                "Pilot",
                String.format(Locale.US, "%.6f, %.6f", d.pilotLat, d.pilotLon)
            )
            DetailRow(
                "Pilot distance",
                String.format(
                    Locale.US, "%.2f km",
                    haversine(d.droneLat, d.droneLon, d.pilotLat, d.pilotLon) / 1000.0
                )
            )
        } else {
            DetailRow("Pilot", "unknown")
        }
        DetailRow("Detections", d.detections.toString())

        Spacer(Modifier.height(16.dp))
        onShowFlights?.let {
            OutlinedButton(
                onClick = it,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show all flights on map")
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("FAA Registration", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                faa.isNullOrEmpty() -> "Looking up..."
                else -> faa
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Phone-clock arrival time of the latest detection, as local time-of-day. */
private fun formatTime(ts: Long): String =
    java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(ts)

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
