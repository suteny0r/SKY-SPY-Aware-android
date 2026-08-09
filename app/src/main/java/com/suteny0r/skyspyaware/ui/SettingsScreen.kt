package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suteny0r.skyspyaware.MqttSettings
import com.suteny0r.skyspyaware.SkySpyViewModel

@Composable
fun SettingsScreen(vm: SkySpyViewModel) {
    var settings by remember { mutableStateOf(vm.settings()) }
    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
    }
}
