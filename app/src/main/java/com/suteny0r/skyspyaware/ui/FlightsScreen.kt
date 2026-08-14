package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.FlightSummary
import com.suteny0r.skyspyaware.SkySpyViewModel
import com.suteny0r.skyspyaware.ui.formatDistance
import com.suteny0r.skyspyaware.ui.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FLIGHT_DATE = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/** Tab listing every individual flight across all history, newest first. */
@Composable
fun FlightsScreen(vm: SkySpyViewModel, onSelectFlight: (FlightSummary) -> Unit) {
    val flights by vm.flights.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshFlights()
    }

    if (flights.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Computing flights from full history...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    // How many flights each drone has, to flag repeat flyers.
    val perDrone = flights.groupingBy { it.droneKey }.eachCount()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(flights, key = { "${it.droneKey}:${it.startTs}" }) { f ->
            FlightRow(
                f = f,
                totalForDrone = perDrone[f.droneKey] ?: 1,
                onClick = { onSelectFlight(f) }
            )
        }
    }
}

@Composable
private fun FlightRow(f: FlightSummary, totalForDrone: Int, onClick: () -> Unit) {
    val repeatFlyer = totalForDrone > 2
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    f.droneKey,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (repeatFlyer) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF1565C0)
                    ) {
                        Text(
                            "$totalForDrone flights",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
            if (f.make.isNotEmpty() || f.model.isNotEmpty()) {
                Text(
                    "${if (f.make.isNotEmpty()) f.make else ""}" +
                        "${if (f.make.isNotEmpty() && f.model.isNotEmpty()) " " else ""}" +
                        f.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${FLIGHT_DATE.format(Date(f.startTs))}  •  " +
                    "${formatDuration(f.durationMs)}  •  ${formatDistance(f.distanceM)}  •  " +
                    "${f.positions} pts",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
