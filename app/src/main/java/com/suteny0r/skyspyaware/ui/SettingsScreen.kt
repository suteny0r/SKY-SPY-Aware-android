package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.HISTORY_SCALE_ALL
import com.suteny0r.skyspyaware.HISTORY_SCALES
import com.suteny0r.skyspyaware.MqttSettings
import com.suteny0r.skyspyaware.SkySpyViewModel
import java.util.Locale

@Composable
fun SettingsScreen(vm: SkySpyViewModel) {
    var settings by remember { mutableStateOf(vm.settings()) }
    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()
    val historyStats by vm.historyStats.collectAsState()
    val autoPruneScale by vm.autoPruneScale.collectAsState()
    val historyScale by vm.historyScale.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshHistoryStats()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("MQTT Broker", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = settings.broker,
            onValueChange = { settings = settings.copy(broker = it) },
            label = { Text("Broker host") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.port.toString(),
            onValueChange = { settings = settings.copy(port = it.toIntOrNull() ?: 8883) },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.user,
            onValueChange = { settings = settings.copy(user = it) },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.pass,
            onValueChange = { settings = settings.copy(pass = it) },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.topic,
            onValueChange = { settings = settings.copy(topic = it) },
            label = { Text("Topic prefix") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text("TLS", modifier = Modifier.weight(1f).padding(top = 12.dp))
            Switch(
                checked = settings.tls,
                onCheckedChange = { settings = settings.copy(tls = it) }
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveSettings(settings); vm.connect() },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (connected) "Reconnect" else "Connect")
            }
            OutlinedButton(
                onClick = { vm.disconnect() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Status: $status",
            style = MaterialTheme.typography.bodyMedium,
            color = if (connected) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.error
        )
        Text(
            text = "Default mode: MQTT subscriber. Connects to the shared " +
                    "skyspy feed. Edit settings to point at another broker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text("History", style = MaterialTheme.typography.titleMedium)
        Text(
            "${String.format(Locale.US, "%,d", historyStats.count)} stored detections",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "${String.format(Locale.US, "%,d", historyStats.drones)} unique drones",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "${formatBytes(historyStats.bytes)} on disk",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "History window",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            var windowMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { windowMenu = true }) {
                    Text(
                        HISTORY_SCALES.firstOrNull { it.first == historyScale }?.second
                            ?: historyScale,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = windowMenu,
                    onDismissRequest = { windowMenu = false }
                ) {
                    HISTORY_SCALES.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                vm.setHistoryScale(value)
                                windowMenu = false
                            }
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Auto-prune after",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            var pruneMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { pruneMenu = true }) {
                    Text(
                        AUTO_PRUNE_OPTIONS.firstOrNull { it.first == autoPruneScale }?.second
                            ?: autoPruneScale,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = pruneMenu,
                    onDismissRequest = { pruneMenu = false }
                ) {
                    AUTO_PRUNE_OPTIONS.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                vm.setAutoPruneScale(value)
                                pruneMenu = false
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.purgeHistory() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Purge history")
        }
        Text(
            text = "History is auto-pruned after the selected period. Purging " +
                    "permanently deletes all stored history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private val AUTO_PRUNE_OPTIONS: List<Pair<String, String>> = HISTORY_SCALES.map { (value, label) ->
    value to if (value == HISTORY_SCALE_ALL) "Infinite" else label
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}
