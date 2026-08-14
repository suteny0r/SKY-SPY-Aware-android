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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

const val HISTORY_SCALE_DAY = "day"
const val HISTORY_SCALE_WEEK = "week"
const val HISTORY_SCALE_MONTH = "month"
const val HISTORY_SCALE_YEAR = "year"
const val HISTORY_SCALE_ALL = "all"

/** History-window scales offered by the map slider dropdown. */
val HISTORY_WINDOW_SCALES: List<Pair<String, String>> = listOf(
    HISTORY_SCALE_DAY to "1 day",
    HISTORY_SCALE_WEEK to "1 week",
    HISTORY_SCALE_MONTH to "1 month",
    HISTORY_SCALE_YEAR to "1 year"
)

/** Retention scales offered by the auto-prune dropdown. */
val AUTO_PRUNE_SCALES: List<Pair<String, String>> = listOf(
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

    /**
     * True when an FAA lookup result means "no registration on file", i.e. the
     * drone is treated as simulator traffic. Any row whose remote-id serial
     * returns no such record in the FCC/FAA registry is simulator data and is
     * excluded from real-world statistics. Both the canonical sentinel and the
     * legacy "No registration data" text are accepted so already-cached lookups
     * are honoured.
     */
    private fun isSimulator(result: String?): Boolean {
        if (result == null) return false
        if (result == FAA_NOT_FOUND) return true
        val r = result.lowercase()
        return "no registration" in r || "not found" in r || "no such" in r
    }

    private const val CONSOLE_LIMIT = 500
    private const val TRAIL_MAX = 20000
    private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
    private const val FAA_RETRY_POLL_MS = 60_000L
    private const val FAA_LOOKUP_INTERVAL_MS = 5_000L
    private const val STATS_RECOMPUTE_DEBOUNCE_MS = 2500L
    private val statsRecomputeLock = Any()
    private var statsRecomputeScheduled = false
    private const val SATELLITE_TTL_MS = 24L * 60 * 60 * 1000
    private const val FLIGHT_QUIET_MS = 150_000L   // 2.5 min without a fix = flight over
    private const val FLIGHT_CLASSIFY_POLL_MS = 30_000L
    private const val FLIGHT_CLASSIFY_COOLDOWN_MS = 30L * 60 * 1000
    // Only drones seen within this window are candidates for an automatic
    // satellite scan. After an import of months of history the retained set
    // can be hundreds of drones; scanning all of them at once allocates a
    // bitmap stack per drone and OOMs the process. Historical drones simply
    // keep whatever cache they already have.
    private const val SATELLITE_SCAN_WINDOW_MS = 24L * 60 * 60 * 1000
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

    private val _stats = MutableStateFlow<Statistics?>(null)
    val stats: StateFlow<Statistics?> = _stats.asStateFlow()

    private val _flights = MutableStateFlow<List<FlightSummary>>(emptyList())
    val flights: StateFlow<List<FlightSummary>> = _flights.asStateFlow()

    private val _droneNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val droneNotes: StateFlow<Map<String, String>> = _droneNotes.asStateFlow()

    private val _noteSuggestions = MutableStateFlow<List<String>>(emptyList())
    val noteSuggestions: StateFlow<List<String>> = _noteSuggestions.asStateFlow()

    private val _faaPlatform = MutableStateFlow<Map<String, String>>(emptyMap())
    val faaPlatform: StateFlow<Map<String, String>> = _faaPlatform.asStateFlow()

    private val satelliteCounts = HashMap<String, Map<String, Int>>()
    private val satelliteTs = HashMap<String, Long>()
    private val _satellite = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val satellite: StateFlow<Map<String, Map<String, Int>>> = _satellite.asStateFlow()

    private val droneMap = LinkedHashMap<String, Drone>()
    private val consoleBuffer = ArrayDeque<String>()
    private val faaCache = HashMap<String, String>()
    private val faaRetryAt = HashMap<String, Long>()
    private val faaAttempts = HashMap<String, Int>()
    private val faaQueue = ArrayDeque<String>()
    private val faaQueued = HashSet<String>()
    private val faaPlatformLabels = HashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ageJob: Job? = null
    private var faaLookupJob: Job? = null
    private val faaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Drone keys with an in-flight completion classify, to avoid overlap. */
    private val flightClassifying = HashSet<String>()

    /** Last wall-clock time a drone was auto-classified, to throttle rescans. */
    private val flightClassifiedTs = HashMap<String, Long>()

    private var lastPruneMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        createChannels()
        _historyScale.value = when (val s = settingsRepo.getHistoryScale()) {
            HISTORY_SCALE_ALL -> HISTORY_SCALE_YEAR
            else -> s
        }
        _historyMinutes.value = settingsRepo.getHistoryMinutes()
        _autoPruneScale.value = settingsRepo.getAutoPruneScale()
        _droneNotes.value = try {
            cache.loadDroneNotes()
        } catch (_: Exception) {
            emptyMap()
        }
        _noteSuggestions.value = try {
            cache.loadNoteSuggestions(12)
        } catch (_: Exception) {
            emptyList()
        }
        mqtt.onLine = { line -> handleLine(line) }
        ageJob = scope.launch {
            while (isActive) {
                ageOut()
                pruneExpired()
                delay(5000)
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    classifyCompletedFlights()
                } catch (_: Exception) {
                }
                delay(FLIGHT_CLASSIFY_POLL_MS)
            }
        }
        faaLookupJob = startFaaWorker()
        cacheScope.launch {
            // One-time purge of satellite counts written by the broken YOLO
            // decode (phantom swimming pools in the thousands).
            if (!settingsRepo.isSatelliteCachePurged()) {
                try {
                    cache.clearSatelliteCache()
                } catch (_: Exception) {
                }
                settingsRepo.markSatelliteCachePurged()
            }
            // Seed the in-memory cache with persisted lookups so restarts
            // don't re-hit the API for records we already resolved.
            try {
                cache.loadFaaCache().forEach { (id, text) ->
                    synchronized(faaCache) { if (!faaCache.containsKey(id)) faaCache[id] = text }
                }
                cache.loadFaaPlatforms().forEach { (id, label) ->
                    synchronized(faaPlatformLabels) { if (!faaPlatformLabels.containsKey(id)) faaPlatformLabels[id] = label }
                }
                cache.loadSatelliteCache().forEach { (key, pair) ->
                    synchronized(satelliteCounts) {
                        satelliteCounts[key] = decodeCounts(pair.first)
                        satelliteTs[key] = pair.second
                    }
                }
            } catch (_: Exception) {
            }
            _faa.value = HashMap(faaCache)
            _faaPlatform.value = HashMap(faaPlatformLabels)
            _satellite.value = HashMap(satelliteCounts)
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
            // Null-island coordinates (exact (0,0) or the small box around it)
            // are the beacon's "no position known" sentinel. Keep the last
            // known position and never grow the trail from the ocean point.
            val hasPos = isValidPosition(d.droneLat, d.droneLon)
            val effectiveLat: Double
            val effectiveLon: Double
            val trail: List<TrailPoint>
            if (hasPos) {
                // A drone can broadcast on multiple MACs (AP beacon vs NAN) with
                // different positions. Only advance when THIS MAC reports a
                // changed position, or the drone position flip-flops every frame.
                val macChanged = lastForMac == null ||
                    lastForMac.first != d.droneLat || lastForMac.second != d.droneLon
                if (macChanged) macPositions[d.mac] = d.droneLat to d.droneLon
                effectiveLat = if (macChanged) d.droneLat else (prev?.droneLat ?: d.droneLat)
                effectiveLon = if (macChanged) d.droneLon else (prev?.droneLon ?: d.droneLon)
                trail = if (macChanged) {
                    ((prev?.trail ?: emptyList()) + TrailPoint(ts, effectiveLat, effectiveLon))
                        .takeLast(TRAIL_MAX)
                } else {
                    prev?.trail ?: listOf(TrailPoint(ts, effectiveLat, effectiveLon))
                }
            } else {
                effectiveLat = prev?.droneLat ?: 0.0
                effectiveLon = prev?.droneLon ?: 0.0
                trail = prev?.trail ?: emptyList()
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
        if (faa && d.basicId.isNotBlank()) enqueueFaaLookup(d.basicId)
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
            enqueueAllFaa()
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
            enqueueAllFaa()
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

    private fun enqueueFaaLookup(basicId: String) {
        synchronized(faaCache) {
            // Already resolved, in-flight, or waiting in the queue: skip.
            if (faaCache.containsKey(basicId) || basicId in faaQueued) return
            faaQueued.add(basicId)
            faaQueue.addLast(basicId)
        }
    }

    /** Enqueue lookups for every known drone so the whole list resolves in the background. */
    private fun enqueueAllFaa() {
        val ids = synchronized(droneMap) {
            droneMap.values.map { it.basicId }.filter { it.isNotBlank() }.toSet()
        }
        ids.forEach { enqueueFaaLookup(it) }
    }

    /**
     * Single background worker resolving lookups one at a time with a minimum
     * interval between requests, so we never burst the FAA endpoint. Fresh
     * queue entries take priority; failed lookups are retried later with
     * backoff when the queue is idle.
     */
    private fun startFaaWorker(): Job? {
        if (faaLookupJob?.isActive == true) return faaLookupJob
        return faaScope.launch {
            while (isActive) {
                val id = nextFaaJob()
                if (id == null) {
                    delay(FAA_RETRY_POLL_MS)
                    continue
                }
                synchronized(faaCache) { faaCache[id] = "" } // mark in-flight
                val result = try {
                    FaaClient.lookup(id)
                } catch (e: Exception) {
                    FaaLookup("Lookup failed: ${e.message}", true)
                }
                synchronized(faaCache) {
                    if (result.retriable) {
                        val attempt = (faaAttempts[id] ?: 0) + 1
                        faaAttempts[id] = attempt
                        faaRetryAt[id] = System.currentTimeMillis() + faaBackoffMs(attempt)
                        faaCache[id] = "${result.text} (retrying)"
                    } else {
                        faaCache[id] = result.text
                        faaRetryAt.remove(id)
                        faaAttempts.remove(id)
                        val platform = PublicSafetyPlatform.label(result.make, result.model)
                        faaPlatformLabels[id] = platform ?: ""
                        try {
                            cache.saveFaaCache(id, result.text, platform)
                        } catch (_: Exception) {
                        }
                    }
                }
                _faa.value = HashMap(faaCache)
                _faaPlatform.value = HashMap(faaPlatformLabels)
                // A definitive result (model resolved, or no-registration ->
                // simulator) changes the drone inventory, so recompute stats
                // on a short debounce so the Drones/Flights tabs stay current
                // as the background lookup crawl progresses.
                scheduleStatsRecompute()
                delay(FAA_LOOKUP_INTERVAL_MS)
            }
        }
    }

    /** Debounced recompute so FAA lookups (models + simulator tagging) surface
     *  in the stats without reopening the tab. */
    private fun scheduleStatsRecompute() {
        synchronized(statsRecomputeLock) {
            if (statsRecomputeScheduled) return
            statsRecomputeScheduled = true
        }
        cacheScope.launch {
            delay(STATS_RECOMPUTE_DEBOUNCE_MS)
            synchronized(statsRecomputeLock) { statsRecomputeScheduled = false }
            computeStats(emitFlights = true)
        }
    }

    private fun nextFaaJob(): String? = synchronized(faaCache) {
        if (faaQueue.isNotEmpty()) {
            faaQueue.removeFirst().also { faaQueued.remove(it) }
        } else {
            val now = System.currentTimeMillis()
            faaRetryAt.entries
                .firstOrNull { it.value <= now && faaCache[it.key] != "" }
                ?.key
        }
    }

    private fun faaBackoffMs(attempt: Int): Long = when {
        attempt <= 1 -> 5 * 60_000L
        attempt == 2 -> 15 * 60_000L
        attempt == 3 -> 60 * 60_000L
        else -> 4 * 60 * 60_000L
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
        HISTORY_SCALE_YEAR -> 365 * 24 * 60
        else -> 24 * 60
    }

    /** In-memory drone-state retention for the current scale. */
    private fun retentionMs(): Long = when (_historyScale.value) {
        HISTORY_SCALE_WEEK -> 7L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_MONTH -> 30L * 24 * 60 * 60 * 1000
        HISTORY_SCALE_YEAR -> 365L * 24 * 60 * 60 * 1000
        else -> 24L * 60 * 60 * 1000
    }

    fun setHistoryScale(scale: String) {
        val v = if (HISTORY_WINDOW_SCALES.any { it.first == scale }) scale else HISTORY_SCALE_DAY
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
        val v = if (AUTO_PRUNE_SCALES.any { it.first == scale }) scale else HISTORY_SCALE_ALL
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

    /**
     * Auto-classify drones whose flight has just ended: a drone that has had no
     * new fix for [FLIGHT_QUIET_MS] and has a real trail gets a combined
     * wide+tight satellite scan once, so the role assessment reflects the full
     * flight footprint rather than the very short early path. Runs on a poll
     * every [FLIGHT_CLASSIFY_POLL_MS]. A drone is only re-scanned after a new
     * flight (a newer lastSeen) goes quiet again.
     */
    private suspend fun classifyCompletedFlights() {
        val now = System.currentTimeMillis()
        val candidates = synchronized(droneMap) { droneMap.values.toList() }
        for (d in candidates) {
            val key = d.key
            // Skip drones mid-classify or that are still reporting fixes.
            var busy = false
            synchronized(flightClassifying) {
                if (key in flightClassifying) busy = true else flightClassifying.add(key)
            }
            if (busy) continue
            try {
                if (now - d.lastSeen < FLIGHT_QUIET_MS) continue
                // Historical drones (from imports / long retention) would all
                // look "flight complete"; only auto-classify recent activity.
                if (now - d.lastSeen > SATELLITE_SCAN_WINDOW_MS) continue
                // Skip if this flight was already classified recently.
                val lastClassified = synchronized(flightClassifiedTs) {
                    flightClassifiedTs[key] ?: 0L
                }
                if (now - lastClassified < FLIGHT_CLASSIFY_COOLDOWN_MS) continue
                val pts = d.trail.filter { isValidPosition(it.lat, it.lon) }
                if (pts.size < 2) continue
                val tight = SatelliteAnalyzer.boundsOfPoints(
                    pts.map { it.lat to it.lon }, 150.0
                ) ?: continue
                val wide = SatelliteAnalyzer.boundsOfPoints(
                    pts.map { it.lat to it.lon }, 700.0
                ) ?: continue
                val scan = try {
                    SatelliteAnalyzer.scanCombined(appContext, wide, tight)
                } catch (_: Exception) {
                    SatelliteAnalyzer.AreaScan(emptyMap())
                }
                applySatelliteScan(key, scan.counts)
                synchronized(flightClassifiedTs) {
                    flightClassifiedTs[key] = now
                }
            } finally {
                synchronized(flightClassifying) {
                    flightClassifying.remove(key)
                }
            }
        }
    }

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

    /** Recompute statistics from the full retained history (background). */
    fun refreshStats() {
        cacheScope.launch {
            computeStats()
            satellitePass()
            computeStats(emitFlights = true)
        }
    }

    /** Recompute just the flight list from full history (used by the Flights tab). */
    fun refreshFlights() {
        cacheScope.launch {
            val faaText = synchronized(faaCache) { HashMap(faaCache) }
            try {
                cache.distinctBasicIds().forEach { enqueueFaaLookup(it) }
            } catch (_: Exception) {
            }
            val dbFlights = try {
                cache.loadFlights()
            } catch (_: Exception) {
                emptyList()
            }
            _flights.value = if (dbFlights.isNotEmpty()) {
                val simulatorKeys = synchronized(faaCache) {
                    faaCache.filterValues { isSimulator(it) }.keys.toSet()
                }
                StatisticsCalculator.flightsFromDb(dbFlights, faaText, simulatorKeys)
            } else {
                val rows = try {
                    cache.loadSince(0L)
                } catch (_: Exception) {
                    emptyList()
                }
                val simulatorKeys = synchronized(faaCache) {
                    faaCache.filterValues { isSimulator(it) }.keys.toSet()
                }
                StatisticsCalculator.computeFlights(rows, simulatorKeys, faaText)
            }
        }
    }

    /** Guards against overlapping [satellitePass] runs (refreshStats can be
     *  triggered repeatedly by applySatelliteScan / classifyCompletedFlights). */
    private val satellitePassBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun computeStats(emitFlights: Boolean = false) {
        val rows = try {
            cache.loadSince(0L)
        } catch (_: Exception) {
            emptyList()
        }
        // Simulator drones are identified by basicIds that fail the FAA
        // registration lookup.
        val simulatorKeys = synchronized(faaCache) {
            faaCache.filterValues { isSimulator(it) }.keys.toSet()
        }
        val faaText = synchronized(faaCache) { HashMap(faaCache) }
        val sat = synchronized(satelliteCounts) { HashMap(satelliteCounts) }
        // Enqueue registration lookups for every basic_id in history so the
        // stats make/model attribution fills in over time, even for drones
        // outside the current map window. The FAA worker throttles to one
        // request every few seconds, so this is a slow background crawl.
        try {
            cache.distinctBasicIds().forEach { enqueueFaaLookup(it) }
        } catch (_: Exception) {
        }
        _stats.value = try {
            StatisticsCalculator.compute(
                rows, simulatorKeys, sat, faaText, _droneNotes.value
            )
        } catch (_: Exception) {
            null
        }
        if (emitFlights) {
            val dbFlights = try {
                cache.loadFlights()
            } catch (_: Exception) {
                emptyList()
            }
            _flights.value = if (dbFlights.isNotEmpty()) {
                StatisticsCalculator.flightsFromDb(dbFlights, faaText, simulatorKeys)
            } else {
                StatisticsCalculator.computeFlights(rows, simulatorKeys, faaText)
            }
        }
    }

    /**
     * Refresh satellite object scans for drones whose cached scan is stale.
     * Runs one scan at a time with a delay so we never burst the tile server.
     */
    private suspend fun satellitePass() {
        if (!satellitePassBusy.compareAndSet(false, true)) return
        try {
            val ttl = System.currentTimeMillis() - SATELLITE_TTL_MS
            val cutoff = System.currentTimeMillis() - SATELLITE_SCAN_WINDOW_MS
            val keys = synchronized(droneMap) { droneMap.keys.toList() }
            for (key in keys) {
                val ts = synchronized(satelliteCounts) { satelliteTs[key] ?: 0L }
                if (ts > ttl) continue
                val d = synchronized(droneMap) { droneMap[key] } ?: continue
                if (!isValidPosition(d.droneLat, d.droneLon)) continue
                // Skip historical drones: only refresh live/very-recent activity.
                if (d.lastSeen < cutoff) continue
                val scan = try {
                    SatelliteAnalyzer.scan(appContext, d.droneLat, d.droneLon)
                } catch (_: Exception) {
                    SatelliteAnalyzer.AreaScan(emptyMap())
                }
                val now = System.currentTimeMillis()
                synchronized(satelliteCounts) {
                    satelliteCounts[key] = scan.counts
                    satelliteTs[key] = now
                }
                try {
                    cache.saveSatelliteCache(key, encodeCounts(scan.counts), now)
                } catch (_: Exception) {
                }
                delay(1000L)
            }
            _satellite.value = synchronized(satelliteCounts) { HashMap(satelliteCounts) }
        } finally {
            satellitePassBusy.set(false)
        }
    }

    private fun encodeCounts(counts: Map<String, Int>): String = JSONObject(counts).toString()

    private fun decodeCounts(json: String): Map<String, Int> {
        if (json.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, Int>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optInt(k, 0)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Write the full history database to [out]. */
    suspend fun exportHistory(out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            cache.exportTo(out)
        } catch (_: Exception) {
            false
        }
    }

    /** Replace the history database with the contents of [input] and rebuild state. */
    suspend fun importHistory(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            cache.importFrom(input)
        } catch (_: Exception) {
            false
        }
        if (ok) rebuildAll()
        ok
    }

    /**
     * Load a database bundled in the APK assets (e.g. a large sample dataset
     * for exercising analytics). Replaces the on-device history like an import.
     */
    suspend fun importBundledDataset(assetName: String): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            appContext.assets.open(assetName).use { cache.importFrom(it) }
        } catch (_: Exception) {
            false
        }
        if (ok) rebuildAll()
        ok
    }

    /** Full ordered flight trail for one drone from the entire database. */
    suspend fun loadDroneFlights(key: String): List<TrailPoint> = withContext(Dispatchers.IO) {
        try {
            cache.loadDroneTrail(key)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Position fixes for one drone within a time window (a single flight). */
    suspend fun loadDroneFlights(key: String, fromTs: Long, toTs: Long): List<TrailPoint> =
        withContext(Dispatchers.IO) {
            try {
                cache.loadDroneTrail(key, fromTs, toTs)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Full ordered trail plus pilot positions for one drone. */
    suspend fun loadDroneFlightsWithPilot(key: String): List<TrailPointWithPilot> =
        withContext(Dispatchers.IO) {
            try {
                cache.loadDroneTrailWithPilot(key)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Trail plus pilot positions for one drone within a time window. */
    suspend fun loadDroneFlightsWithPilot(
        key: String,
        fromTs: Long,
        toTs: Long
    ): List<TrailPointWithPilot> = withContext(Dispatchers.IO) {
        try {
            cache.loadDroneTrailWithPilot(key, fromTs, toTs)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Every flight trail across all drones (for the Flights tab "All" view). */
    suspend fun loadAllTrails(): List<FlightTrail> = withContext(Dispatchers.IO) {
        try {
            cache.loadAllFlightTrails()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Persist a note for a drone; an empty/blank note clears it. */
    fun setDroneNote(key: String, note: String) {
        val trimmed = note.trim()
        try {
            if (trimmed.isEmpty()) {
                cache.clearDroneNote(key)
            } else {
                cache.saveDroneNote(key, trimmed)
                cache.recordNoteHistory(trimmed)
            }
        } catch (_: Exception) {
        }
        _droneNotes.value = try {
            cache.loadDroneNotes()
        } catch (_: Exception) {
            emptyMap()
        }
        _noteSuggestions.value = try {
            cache.loadNoteSuggestions(12)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Store an explicit satellite scan (e.g. from the flights screen) for one
     * drone and immediately recompute statistics so role assessments reflect
     * the fresh object counts instead of stale cached ones.
     */
    fun applySatelliteScan(key: String, counts: Map<String, Int>) {
        val now = System.currentTimeMillis()
        synchronized(satelliteCounts) {
            satelliteCounts[key] = counts
            satelliteTs[key] = now
        }
        try {
            cache.saveSatelliteCache(key, encodeCounts(counts), now)
        } catch (_: Exception) {
        }
        _satellite.value = synchronized(satelliteCounts) { HashMap(satelliteCounts) }
        refreshStats()
    }

    /**
     * Force a fresh satellite object scan around a drone's trail and apply it,
     * so the role assessment is recalculated from live imagery. No-op if the
     * drone has no known position.
     */
    suspend fun reclassify(context: Context, key: String): Map<String, Int>? {
        val d = _drones.value.firstOrNull { it.key == key } ?: return null
        if (!isValidPosition(d.droneLat, d.droneLon)) return null
        val trail = d.trail
            .filter { isValidPosition(it.lat, it.lon) }
            .map { it.lat to it.lon }
        val scan = if (trail.size >= 2) {
            val tight = SatelliteAnalyzer.boundsOfPoints(trail, 150.0) ?: return null
            val wide = SatelliteAnalyzer.boundsOfPoints(trail, 700.0) ?: return null
            SatelliteAnalyzer.scanCombined(context, wide, tight)
        } else {
            val tight = SatelliteAnalyzer.boundsOfPoint(d.droneLat, d.droneLon, 150.0)
            val wide = SatelliteAnalyzer.boundsOfPoint(d.droneLat, d.droneLon, 700.0)
            SatelliteAnalyzer.scanCombined(context, wide, tight)
        }
        applySatelliteScan(key, scan.counts)
        return scan.counts
    }

    /** Reset in-memory state and rebuild it from the current database. */
    private suspend fun rebuildAll() {
        synchronized(faaCache) {
            faaCache.clear()
            faaRetryAt.clear()
            faaAttempts.clear()
            faaQueue.clear()
            faaQueued.clear()
            faaPlatformLabels.clear()
            try {
                cache.loadFaaCache().forEach { (id, text) -> faaCache[id] = text }
                cache.loadFaaPlatforms().forEach { (id, label) -> faaPlatformLabels[id] = label }
            } catch (_: Exception) {
            }
        }
        _faa.value = HashMap(faaCache)
        _faaPlatform.value = HashMap(faaPlatformLabels)

        synchronized(satelliteCounts) {
            satelliteCounts.clear()
            satelliteTs.clear()
            try {
                cache.loadSatelliteCache().forEach { (key, pair) ->
                    satelliteCounts[key] = decodeCounts(pair.first)
                    satelliteTs[key] = pair.second
                }
            } catch (_: Exception) {
            }
        }
        _satellite.value = HashMap(satelliteCounts)

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
        enqueueAllFaa()
        refreshHistoryStats()
        refreshStats()
    }

    /** Delete all retained detection history and clear live drone state. */
    fun purgeHistory() {
        cacheScope.launch {
            try {
                cache.purge()
            } catch (_: Exception) {
            }
            try {
                cache.clearFaaCache()
            } catch (_: Exception) {
            }
            try {
                cache.clearSatelliteCache()
            } catch (_: Exception) {
            }
            synchronized(faaCache) {
                faaCache.clear()
                faaRetryAt.clear()
                faaAttempts.clear()
                faaPlatformLabels.clear()
            }
            _faa.value = HashMap(faaCache)
            _faaPlatform.value = HashMap(faaPlatformLabels)
            synchronized(satelliteCounts) {
                satelliteCounts.clear()
                satelliteTs.clear()
            }
            _satellite.value = HashMap(satelliteCounts)
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
