package com.suteny0r.skyspyaware

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin bridge between the UI and the [DataRepo] singleton. The repo keeps
 * collecting while the app is backgrounded (via [SkySpyService]); this ViewModel
 * only exposes its state and forwards actions.
 */
class SkySpyViewModel(app: Application) : AndroidViewModel(app) {

    init {
        DataRepo.init(app)
        // Resume collection (auto-connect) if it was active before.
        DataRepo.ensureCollecting(app)
    }

    val drones: StateFlow<List<Drone>> = DataRepo.drones
    val console: StateFlow<List<String>> = DataRepo.console
    val status: StateFlow<String> = DataRepo.status
    val connected: StateFlow<Boolean> = DataRepo.connected
    val faa: StateFlow<Map<String, String>> = DataRepo.faa
    val faaPlatform: StateFlow<Map<String, String>> = DataRepo.faaPlatform
    val satellite: StateFlow<Map<String, Map<String, Int>>> = DataRepo.satellite
    val historyMinutes: StateFlow<Int> = DataRepo.historyMinutes
    val historyScale: StateFlow<String> = DataRepo.historyScale
    val autoPruneScale: StateFlow<String> = DataRepo.autoPruneScale
    val pendingSelection: StateFlow<String?> = DataRepo.pendingSelection
    val historyStats: StateFlow<HistoryStats> = DataRepo.historyStats
    val stats: StateFlow<Statistics?> = DataRepo.stats

    fun connect() = DataRepo.startCollecting(getApplication())
    fun disconnect() = DataRepo.stopCollecting(getApplication())

    fun settings(): MqttSettings = DataRepo.settings()
    fun saveSettings(s: MqttSettings) = DataRepo.saveSettings(s)
    fun getMapStyle(): Int = DataRepo.getMapStyle()
    fun setMapStyle(index: Int) = DataRepo.setMapStyle(index)
    fun setHistoryMinutes(minutes: Int) = DataRepo.setHistoryMinutes(minutes)
    fun setHistoryScale(scale: String) = DataRepo.setHistoryScale(scale)
    fun setAutoPruneScale(scale: String) = DataRepo.setAutoPruneScale(scale)
    fun historyMaxMinutes(): Int = DataRepo.historyMaxMinutes()
    fun consumePendingSelection() = DataRepo.consumePendingSelection()
    fun refreshHistoryStats() = DataRepo.refreshHistoryStats()
    fun refreshStats() = DataRepo.refreshStats()
    fun purgeHistory() = DataRepo.purgeHistory()
    suspend fun exportHistory(out: OutputStream): Boolean = DataRepo.exportHistory(out)
    suspend fun importHistory(input: InputStream): Boolean = DataRepo.importHistory(input)
    suspend fun loadDroneFlights(key: String): List<TrailPoint> = DataRepo.loadDroneFlights(key)
    fun applySatelliteScan(key: String, counts: Map<String, Int>) =
        DataRepo.applySatelliteScan(key, counts)
    suspend fun reclassify(key: String): Map<String, Int>? =
        DataRepo.reclassify(getApplication(), key)
}
