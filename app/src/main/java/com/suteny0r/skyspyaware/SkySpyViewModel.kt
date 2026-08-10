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

private const val DRONE_RETENTION_MS = DetectionCache.RETAIN_MS
private const val CONSOLE_LIMIT = 500
private const val TRAIL_MAX = 500
const val MAX_HISTORY_MINUTES = 1440

class SkySpyViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val mqtt = MqttManager(app.applicationContext)
    private val cache: DetectionCache by lazy { DetectionCache(app) }

    private val _drones = MutableStateFlow<List<Drone>>(emptyList())
    val drones: StateFlow<List<Drone>> = _drones.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _faa = MutableStateFlow<Map<String, String>>(emptyMap())
    val faa: StateFlow<Map<String, String>> = _faa.asStateFlow()

    private val _historyMinutes = MutableStateFlow(settingsRepo.getHistoryMinutes())
    val historyMinutes: StateFlow<Int> = _historyMinutes.asStateFlow()

    private val droneMap = LinkedHashMap<String, Drone>()
    private val consoleBuffer = ArrayDeque<String>()
    private val faaCache = HashMap<String, String>()

    private val ageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ageJob: Job? = null
    private val faaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        mqtt.onLine = { line -> handleLine(line) }
        ageJob = ageScope.launch {
            while (isActive) {
                delay(5000)
                ageOut()
            }
        }
        loadCachedHistory()
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
        try {
            cache.insert(d, now)
        } catch (_: Exception) {
        }
        updateDrone(d, now, faa = true)
        _drones.value = droneMap.values.toList()
    }

    /** Rebuild drone state from a detection. [ts] is the arrival timestamp. */
    private fun updateDrone(d: Detection, ts: Long, faa: Boolean) {
        val key = d.basicId.ifBlank { d.mac }
        synchronized(droneMap) {
            val prev = droneMap[key]
            // Skip older frames (e.g. history replay racing a live update).
            if (prev != null && ts < prev.lastSeen) return
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
                ((prev?.trail ?: emptyList()) + TrailPoint(ts, effectiveLat, effectiveLon))
                    .takeLast(TRAIL_MAX)
            } else {
                prev?.trail ?: listOf(TrailPoint(ts, effectiveLat, effectiveLon))
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
                lastSeen = ts,
                detections = (prev?.detections ?: 0) + 1,
                macPositions = macPositions,
                trail = trail
            )
        }
        if (faa && d.basicId.isNotBlank()) faaLookup(d.basicId)
    }

    /** Rebuild drone state from the last 24h of cached detections on startup. */
    private fun loadCachedHistory() {
        cacheScope.launch {
            val now = System.currentTimeMillis()
            try {
                cache.prune(now - DRONE_RETENTION_MS)
                val rows = cache.loadSince(now - DRONE_RETENTION_MS)
                rows.forEach { updateDrone(it.toDetection(), it.ts, faa = true) }
            } catch (_: Exception) {
                // Cache failures must not block live operation.
            }
            _drones.value = droneMap.values.toList()
        }
    }

    private fun faaLookup(basicId: String) {
        synchronized(faaCache) {
            if (faaCache.containsKey(basicId)) return
            faaCache[basicId] = "" // mark in-flight
        }
        faaScope.launch {
            val result = try {
                FaaClient.lookup(basicId)
            } catch (e: Exception) {
                "Lookup failed: ${e.message}"
            }
            synchronized(faaCache) { faaCache[basicId] = result }
            _faa.value = HashMap(faaCache)
        }
    }

    private fun ageOut() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(droneMap) {
            val it = droneMap.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > DRONE_RETENTION_MS) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) {
            try {
                cache.prune(now - DRONE_RETENTION_MS)
            } catch (_: Exception) {
            }
        }
        // Always emit so the UI's history window re-evaluates on a timer.
        _drones.value = droneMap.values.toList()
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
    fun getMapStyle(): Int = settingsRepo.getMapStyle()
    fun setMapStyle(index: Int) = settingsRepo.setMapStyle(index)

    fun setHistoryMinutes(minutes: Int) {
        val v = minutes.coerceIn(0, MAX_HISTORY_MINUTES)
        _historyMinutes.value = v
        settingsRepo.setHistoryMinutes(v)
    }

    override fun onCleared() {
        mqtt.disconnect()
        ageJob?.cancel()
        ageScope.cancel()
        faaScope.cancel()
        cacheScope.cancel()
        super.onCleared()
    }
}
