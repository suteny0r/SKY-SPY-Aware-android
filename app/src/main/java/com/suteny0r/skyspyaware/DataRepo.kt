package com.suteny0r.skyspyaware

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val HISTORY_SCALE_DAY = "day"
const val HISTORY_SCALE_WEEK = "week"
const val HISTORY_SCALE_MONTH = "month"
const val HISTORY_SCALE_ALL = "all"

/** History-window scales offered by the map slider dropdown. */
val HISTORY_SCALES: List<Pair<String, String>> = listOf(
    HISTORY_SCALE_DAY to "1 day",
    HISTORY_SCALE_WEEK to "1 week",
    HISTORY_SCALE_MONTH to "1 month",
    HISTORY_SCALE_ALL to "All"
)

/** Size of the retained drone detection history. */
data class HistoryStats(val count: Long, val drones: Long, val bytes: Long)

/**
 * Singleton data layer shared by the UI (via [SkySpyViewModel]) and the
 * background [SkySpyService]. Owns MQTT, the detection cache and drone state so
 * collection continues while the app is not visible, and stops entirely only
 * when the user explicitly disconnects.
 */
object DataRepo {

    private const val CONSOLE_LIMIT = 500
    private const val TRAIL_MAX = 500
    private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
    const val FOREGROUND_NOTIF_ID = 1
    const val CHANNEL_COLLECTING = "skyspy_collecting"
    const val CHANNEL_DETECTIONS = "skyspy_detections"
    const val EXTRA_DRONE_KEY = "drone_key"

    private lateinit var appContext: Context

    private val settingsRepo by lazy { SettingsRepository(appContext) }
    private val mqtt by lazy { MqttManager(appContext) }
    private val cache by lazy { DetectionCache(appContext) }

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

    private val _historyMinutes = MutableStateFlow(30)
    val historyMinutes: StateFlow<Int> = _historyMinutes.asStateFlow()

    private val _historyScale = MutableStateFlow(HISTORY_SCALE_DAY)
    val historyScale: StateFlow<String> = _historyScale.asStateFlow()

    private val _autoPruneScale = MutableStateFlow(HISTORY_SCALE_ALL)
    val autoPruneScale: StateFlow<String> = _autoPruneScale.asStateFlow()

    private val _pendingSelection = MutableStateFlow<String?>(null)
    val pendingSelection: StateFlow<String?> = _pendingSelection.asStateFlow()

    private val _historyStats = MutableStateFlow(HistoryStats(0, 0, 0))
    val historyStats: StateFlow<HistoryStats> = _historyStats.asStateFlow()

    private val droneMap = LinkedHashMap<String, Drone>()
    private val consoleBuffer = ArrayDeque<String>()
    private val faaCache = HashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ageJob: Job? = null
    private val faaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastPruneMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        createChannels()
        _historyScale.value = settingsRepo.getHistoryScale()
        _historyMinutes.value = settingsRepo.getHistoryMinutes()
        _autoPruneScale.value = settingsRepo.getAutoPruneScale()
        mqtt.onLine = { line -> handleLine(line) }
        ageJob = scope.launch {
            while (isActive) {
                ageOut()
                pruneExpired()
                delay(5000)
            }
        }
        loadCachedHistory()
        refreshHistoryStats()
    }

    // ----- lifecycle: collecting starts/stops -----

    /** Connect and keep collecting in the background until [stopCollecting]. */
    fun startCollecting(context: Context) {
        init(context)
        settingsRepo.setCollectingEnabled(true)
        startForegroundService()
        connect()
    }

    /** Called on app launch and on service restart: resume if we want collection. */
    fun ensureCollecting(context: Context) {
        init(context)
        if (settingsRepo.getCollectingEnabled() && !mqtt.isConnected()) {
            startForegroundService()
            connect()
        }
    }

    /** Disconnect and stop the background service entirely. */
    fun stopCollecting(context: Context) {
        init(context)
        settingsRepo.setCollectingEnabled(false)
        mqtt.disconnect()
        _status.value = "Disconnected"
        _connected.value = false
        stopService()
    }

    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, SkySpyService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun stopService() {
        try {
            appContext.stopService(Intent(appContext, SkySpyService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun connect() {
        val s = settingsRepo.load()
        _status.value = "Connecting..."
        mqtt.connect(s) { status ->
            _status.value = status
            _connected.value = status.startsWith("Connected")
        }
    }

    // ----- data ingestion -----

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
        updateDrone(d, now, faa = true, notify = true)
        _drones.value = droneMap.values.toList()
    }

    private fun updateDrone(d: Detection, ts: Long, faa: Boolean, notify: Boolean) {
        val key = d.basicId.ifBlank { d.mac }
        synchronized(droneMap) {
            val prev = droneMap[key]
            // Skip older frames (e.g. history replay racing a live update).
            if (prev != null && ts < prev.lastSeen) return
            val isNew = prev == null
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
            if (notify && isNew) notifyNewDrone(key, d.mac, d.basicId)
        }
        if (faa && d.basicId.isNotBlank()) faaLookup(d.basicId)
    }

    private fun loadCachedHistory() {
        cacheScope.launch {
            val now = System.currentTimeMillis()
            val retention = retentionMs()
            val cutoff = if (retention == Long.MAX_VALUE) 0L else now - retention
            try {
                // Rebuild live drone state for the selected window. Older
                // detections stay in the DB for long-range analysis.
                val rows = cache.loadSince(cutoff)
                rows.forEach { updateDrone(it.toDetection(), it.ts, faa = true, notify = false) }
            } catch (_: Exception) {
            }
            _drones.value = droneMap.values.toList()
        }
    }

    /** Rebuild live drone state from the DB for the currently selected window. */
    private fun reloadHistory() {
        cacheScope.launch {
            val now = System.currentTimeMillis()
            val retention = retentionMs()
            val cutoff = if (retention == Long.MAX_VALUE) 0L else now - retention
            val rows = try {
                cache.loadSince(cutoff)
            } catch (_: Exception) {
                emptyList()
            }
            synchronized(droneMap) {
                droneMap.clear()
                rows.forEach { updateDrone(it.toDetection(), it.ts, faa = true, notify = false) }
            }
            _drones.value = droneMap.values.toList()
        }
    }

    private fun ageOut() {
        val retention = retentionMs()
        if (retention == Long.MAX_VALUE) return
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(droneMap) {
            val it = droneMap.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > retention) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) {
            _drones.value = droneMap.values.toList()
        }
    }

    // ----- FAA -----

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

    // ----- settings -----

    fun settings(): MqttSettings = settingsRepo.load()
    fun saveSettings(s: MqttSettings) = settingsRepo.save(s)
    fun getMapStyle(): Int = settingsRepo.getMapStyle()
    fun setMapStyle(index: Int) = settingsRepo.setMapStyle(index)

    /** Max slider range (minutes) for the current history-window scale. */
    fun historyMaxMinutes(): Int = when (_historyScale.value) {
        HISTORY_SCALE_WEEK -> 7 * 24 * 60
        HISTORY_SCALE_MONTH -> 30 * 24 * 60
        HISTORY_SCALE_ALL -> Int.MAX_VALUE
        else -> 24 * 60
    }

    /** In-memory drone-state retention for the current scale. */
    private fun retentionMs(): Long = when (_historyScale.value) {
        HISTORY_SCALE_WEEK -> 7L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_MONTH -> 30L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_ALL -> Long.MAX_VALUE
        else -> 24L * 60 * 60 * 1000
    }

    fun setHistoryScale(scale: String) {
        val v = if (HISTORY_SCALES.any { it.first == scale }) scale else HISTORY_SCALE_DAY
        _historyScale.value = v
        settingsRepo.setHistoryScale(v)
        _historyMinutes.value = _historyMinutes.value.coerceAtMost(historyMaxMinutes())
        reloadHistory()
    }

    fun setHistoryMinutes(minutes: Int) {
        val v = minutes.coerceIn(0, historyMaxMinutes())
        _historyMinutes.value = v
        settingsRepo.setHistoryMinutes(v)
    }

    /** How long the database retains detections before auto-prune. */
    private fun pruneRetentionMs(): Long = when (_autoPruneScale.value) {
        HISTORY_SCALE_WEEK -> 7L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_MONTH -> 30L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_ALL -> Long.MAX_VALUE
        else -> 24L * 60 * 60 * 1000
    }

    /** Trim expired detections from the database on a schedule. */
    private fun pruneExpired() {
        val r = pruneRetentionMs()
        if (r == Long.MAX_VALUE) return
        val now = System.currentTimeMillis()
        if (now - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = now
        cacheScope.launch {
            try {
                cache.prune(now - r)
            } catch (_: Exception) {
            }
        }
    }

    /** Select the auto-prune length and immediately drop older history. */
    fun setAutoPruneScale(scale: String) {
        val v = if (HISTORY_SCALES.any { it.first == scale }) scale else HISTORY_SCALE_ALL
        _autoPruneScale.value = v
        settingsRepo.setAutoPruneScale(v)
        pruneNow()
        reloadHistory()
        refreshHistoryStats()
    }

    private fun pruneNow() {
        val r = pruneRetentionMs()
        if (r == Long.MAX_VALUE) return
        cacheScope.launch {
            try {
                cache.prune(System.currentTimeMillis() - r)
            } catch (_: Exception) {
            }
        }
    }

    /** Called when a new-drone notification is tapped. */
    fun selectDroneFromNotification(key: String?) {
        _pendingSelection.value = key
    }

    fun consumePendingSelection() {
        _pendingSelection.value = null
    }

    // ----- history retention -----

    /** Recompute the size of the retained detection history. */
    fun refreshHistoryStats() {
        cacheScope.launch {
            _historyStats.value = try {
                HistoryStats(cache.count(), cache.uniqueDroneCount(), cache.dbSizeBytes())
            } catch (_: Exception) {
                HistoryStats(0, 0, 0)
            }
        }
    }

    /** Delete all retained detection history and clear live drone state. */
    fun purgeHistory() {
        cacheScope.launch {
            try {
                cache.purge()
            } catch (_: Exception) {
            }
            synchronized(droneMap) { droneMap.clear() }
            _drones.value = droneMap.values.toList()
            _historyStats.value = try {
                HistoryStats(cache.count(), cache.uniqueDroneCount(), cache.dbSizeBytes())
            } catch (_: Exception) {
                HistoryStats(0, 0, 0)
            }
        }
    }

    // ----- notifications -----

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = appContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COLLECTING, "Collection status",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while drone data is being collected" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DETECTIONS, "New drones",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a new drone is first detected"
                enableVibration(true)
            }
        )
    }

    fun buildForegroundNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(appContext, CHANNEL_COLLECTING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SKY-SPY-Aware")
            .setContentText("Collecting drone detection data")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyNewDrone(key: String, mac: String, basicId: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val pi = PendingIntent.getActivity(
            appContext, key.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_DRONE_KEY, key)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(appContext, CHANNEL_DETECTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New drone detected")
            .setContentText("${basicId.ifBlank { mac }}  (${mac})")
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(key.hashCode(), notif)
        } catch (_: Exception) {
        }
    }
}
