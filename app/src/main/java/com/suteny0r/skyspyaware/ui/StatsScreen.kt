package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.DroneStats
import com.suteny0r.skyspyaware.LaunchType
import com.suteny0r.skyspyaware.PilotRole
import com.suteny0r.skyspyaware.SkySpyViewModel
import com.suteny0r.skyspyaware.Statistics
import java.util.Locale

private val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val HOUR_LABELS = listOf("0", "", "", "", "", "", "6", "", "", "", "", "", "12", "", "", "", "", "", "18", "", "", "", "", "24")
private val ALT_LABELS = listOf("<25", "50", "75", "100", "125", "150", "175", "200+")

private fun roleColor(role: PilotRole): Color = when (role) {
    PilotRole.PORT_INSPECTION -> Color(0xFF00BCD4)
    PilotRole.BEACH_TOURISM -> Color(0xFF00C853)
    PilotRole.SITE_MONITORING -> Color(0xFFFF9800)
    PilotRole.GENERAL -> Color(0xFF2196F3)
    PilotRole.UNKNOWN -> Color(0xFF9E9E9E)
}

@Composable
fun StatsScreen(vm: SkySpyViewModel, onSelect: (String) -> Unit) {
    val stats by vm.stats.collectAsState()

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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Overview", style = MaterialTheme.typography.titleMedium)
        if (s.simulatorDrones > 0) {
            Text(
                "${s.simulatorDrones} simulator drone(s) (${formatCount(s.simulatorDetections)} " +
                    "detections) excluded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Detections", formatCount(s.totalDetections), Modifier.weight(1f))
            StatCell("Drones", formatCount(s.activeDrones), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Flights", formatCount(s.totalFlights), Modifier.weight(1f))
            StatCell("Flight time", formatDuration(s.totalFlightTimeMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Distance", formatDistance(s.totalDistanceM), Modifier.weight(1f))
            StatCell("Max speed", formatSpeed(s.maxSpeedMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell("Avg altitude", "${s.avgAltitudeM.toInt()} m", Modifier.weight(1f))
            StatCell("Max altitude", "${s.maxAltitudeM} m", Modifier.weight(1f))
        }
        if (s.launchCounts.isNotEmpty()) {
            Text(
                "${s.launchCounts[LaunchType.LAND] ?: 0} land-launch  •  " +
                    "${s.launchCounts[LaunchType.SEA] ?: 0} sea-launch",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Busiest days")
        BarChart(s.byDayOfWeek, DAY_LABELS)

        Spacer(Modifier.height(4.dp))
        SectionTitle("Activity by hour of day")
        BarChart(s.byHour, HOUR_LABELS)

        Spacer(Modifier.height(4.dp))
        SectionTitle("Altitude distribution (m)")
        BarChart(s.altitudeHistogram, ALT_LABELS)

        Spacer(Modifier.height(4.dp))
        SectionTitle("Most frequent operators")
        if (s.topDrones.isEmpty()) {
            Text(
                "No position-tracked drones yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            val maxFlights = s.topDrones.maxOf { it.flights }.coerceAtLeast(1)
            s.topDrones.forEach { DroneRow(it, maxFlights, onClick = { onSelect(it.key) }) }
        }

        Spacer(Modifier.height(4.dp))
        SectionTitle("Likely pilot roles")
        if (s.roleCounts.isEmpty()) {
            Text(
                "No data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            val maxRole = s.roleCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            PilotRole.entries.forEach { role ->
                val count = s.roleCounts[role] ?: 0
                if (count > 0) {
                    RoleRow(role, count, maxRole)
                }
            }
            Text(
                "Heuristic guess from where each drone flies (Port of Miami / " +
                    "beach zones, hover patterns and altitude).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BarChart(values: List<Int>, labels: List<String>) {
    if (values.isEmpty()) return
    val maxV = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val barW = size.width / values.size
            val slot = barW * 0.7f
            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / maxV) * size.height
                val left = i * barW + (barW - slot) / 2f
                drawRoundRect(
                    color = if (v > 0) barColor else barColor.copy(alpha = 0.15f),
                    topLeft = Offset(left, size.height - h),
                    size = Size(slot, h),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DroneRow(d: DroneStats, maxFlights: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.key,
                modifier = Modifier.width(120.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(d.flights.toFloat() / maxFlights)
                        .height(14.dp)
                        .background(roleColor(d.role), RoundedCornerShape(4.dp))
                ) {}
            }
            Text(
                "${d.flights}",
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Text(
            "${d.role.label}  •  ${d.launch.label}  •  ${formatDuration(d.flightTimeMs)}  •  " +
                "${formatDistance(d.distanceM)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "speed ${formatSpeed(d.minSpeedMs)}-${formatSpeed(d.maxSpeedMs)}  •  " +
                "avg ${formatSpeed(d.avgSpeedMs)}  •  max alt ${d.maxAltitudeM} m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (d.satellite.isNotEmpty()) {
            Text(
                "satellite: " + d.satellite.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .joinToString("  •  ") { "${it.value} ${it.key}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RoleRow(role: PilotRole, count: Int, max: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            role.label,
            modifier = Modifier.width(180.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp)
                )
        ) {
            Box(
                Modifier
                    .fillMaxWidth(count.toFloat() / max)
                    .height(12.dp)
                    .background(roleColor(role), RoundedCornerShape(4.dp))
            ) {}
        }
        Text(
            "$count",
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun formatCount(n: Number): String = String.format(Locale.US, "%,d", n.toLong())

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatDistance(m: Double): String =
    if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000)
    else String.format(Locale.US, "%.0f m", m)

private fun formatSpeed(ms: Double): String =
    String.format(Locale.US, "%.1f mph", ms * 2.2369362920544)
