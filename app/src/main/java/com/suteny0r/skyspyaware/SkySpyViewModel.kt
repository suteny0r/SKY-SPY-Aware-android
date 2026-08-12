package com.suteny0r.skyspyaware

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

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
    val historyMinutes: StateFlow<Int> = DataRepo.historyMinutes
    val historyScale: StateFlow<String> = DataRepo.historyScale
    val autoPruneScale: StateFlow<String> = DataRepo.autoPruneScale
    val pendingSelection: StateFlow<String?> = DataRepo.pendingSelection
    val historyStats: StateFlow<HistoryStats> = DataRepo.historyStats

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
    fun purgeHistory() = DataRepo.purgeHistory()
}
