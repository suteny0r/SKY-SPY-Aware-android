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
    val pendingSelection: StateFlow<String?> = DataRepo.pendingSelection

    fun connect() = DataRepo.startCollecting(getApplication())
    fun disconnect() = DataRepo.stopCollecting(getApplication())

    fun settings(): MqttSettings = DataRepo.settings()
    fun saveSettings(s: MqttSettings) = DataRepo.saveSettings(s)
    fun getMapStyle(): Int = DataRepo.getMapStyle()
    fun setMapStyle(index: Int) = DataRepo.setMapStyle(index)
    fun setHistoryMinutes(minutes: Int) = DataRepo.setHistoryMinutes(minutes)
    fun consumePendingSelection() = DataRepo.consumePendingSelection()
}
