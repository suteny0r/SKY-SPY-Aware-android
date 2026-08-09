package com.suteny0r.skyspyaware

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DRONE_TIMEOUT_MS = 60_000L
private const val CONSOLE_LIMIT = 500
private const val TRAIL_MAX = 60

class SkySpyViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val mqtt = MqttManager(app.applicationContext)

    private val _drones = MutableStateFlow<List<Drone>>(emptyList())
    val drones: StateFlow<List<Drone>> = _drones.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val droneMap = LinkedHashMap<String, Drone>()
    private val consoleBuffer = ArrayDeque<String>()

    private val ageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ageJob: Job? = null

    init {
        mqtt.onLine = { line -> handleLine(line) }
        ageJob = ageScope.launch {
            while (isActive) {
                delay(5000)
                ageOut()
            }
        }
        // Auto-connect to the stored broker on startup.
        connect()
    }

    private fun handleLine(line: String) {
        synchronized(consoleBuffer) {
            consoleBuffer.addLast(line)
            while (consoleBuffer.size > CONSOLE_LIMIT) consoleBuffer.removeFirst()
            _console.value = consoleBuffer.toList()
        }
        val d = DetectionParser.parse(line) ?: return
        val now = System.currentTimeMillis()
        val key = d.basicId.ifBlank { d.mac }
        synchronized(droneMap) {
            val prev = droneMap[key]
            val macPositions = (prev?.macPositions ?: emptyMap()).toMutableMap()
            val lastForMac = macPositions[d.mac]
            // A drone can broadcast on multiple MACs (AP beacon vs NAN) with
            // different positions. Only advance when THIS MAC reports a
            // changed position, or the drone position flip-flops every frame.
            val macChanged = lastForMac == null ||
                lastForMac.first != d.droneLat || lastForMac.second != d.droneLon
            if (macChanged) macPositions[d.mac] = d.droneLat to d.droneLon

            val effectiveLat = if (macChanged) d.droneLat else (prev?.droneLat ?: d.droneLat)
            val effectiveLon = if (macChanged) d.droneLon else (prev?.droneLon ?: d.droneLon)
            val trail = if (macChanged) {
                ((prev?.trail ?: emptyList()) + (effectiveLat to effectiveLon))
                    .takeLast(TRAIL_MAX)
            } else {
                prev?.trail ?: listOf(effectiveLat to effectiveLon)
            }

            droneMap[key] = Drone(
                key = key,
                mac = d.mac,
                rssi = d.rssi,
                droneLat = effectiveLat,
                droneLon = effectiveLon,
                droneAltitude = d.droneAltitude,
                pilotLat = d.pilotLat,
                pilotLon = d.pilotLon,
                basicId = d.basicId,
                lastSeen = now,
                detections = (prev?.detections ?: 0) + 1,
                macPositions = macPositions,
                trail = trail
            )
            _drones.value = droneMap.values.toList()
        }
    }

    private fun ageOut() {
        val now = System.currentTimeMillis()
        synchronized(droneMap) {
            val it = droneMap.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > DRONE_TIMEOUT_MS) it.remove()
            }
            _drones.value = droneMap.values.toList()
        }
    }

    fun connect() {
        val settings = settingsRepo.load()
        _status.value = "Connecting..."
        mqtt.connect(settings) { s ->
            _status.value = s
            _connected.value = s.startsWith("Connected")
        }
    }

    fun disconnect() {
        mqtt.disconnect()
        _status.value = "Disconnected"
        _connected.value = false
    }

    fun settings(): MqttSettings = settingsRepo.load()
    fun saveSettings(s: MqttSettings) = settingsRepo.save(s)

    override fun onCleared() {
        mqtt.disconnect()
        ageJob?.cancel()
        ageScope.cancel()
        super.onCleared()
    }
}
