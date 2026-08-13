package com.suteny0r.skyspyaware

import android.content.Context
import android.content.SharedPreferences

data class MqttSettings(
    val broker: String,
    val port: Int,
    val tls: Boolean,
    val user: String,
    val pass: String,
    val topic: String
)

/**
 * Persists MQTT connection settings. Ships with the public subscribe-only
 * consumer credentials so the app connects to the shared feed out of the box.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("skyspy_prefs", Context.MODE_PRIVATE)

    fun load(): MqttSettings = MqttSettings(
        broker = prefs.getString("broker", DEFAULT_BROKER)!!,
        port = prefs.getInt("port", DEFAULT_PORT),
        tls = prefs.getBoolean("tls", DEFAULT_TLS),
        user = prefs.getString("user", DEFAULT_USER)!!,
        pass = prefs.getString("pass", DEFAULT_PASS)!!,
        topic = prefs.getString("topic", DEFAULT_TOPIC)!!
    )

    fun save(s: MqttSettings) {
        prefs.edit()
            .putString("broker", s.broker)
            .putInt("port", s.port)
            .putBoolean("tls", s.tls)
            .putString("user", s.user)
            .putString("pass", s.pass)
            .putString("topic", s.topic)
            .apply()
    }

    /** Map style index into [com.suteny0r.skyspyaware.ui.MAP_STYLES]. */
    fun getMapStyle(): Int = prefs.getInt("mapStyle", 0)

    fun setMapStyle(index: Int) {
        prefs.edit().putInt("mapStyle", index).apply()
    }

    /** History window shown on the map, in minutes (0 = live only). */
    fun getHistoryMinutes(): Int = prefs.getInt("historyMinutes", DEFAULT_HISTORY_MINUTES)

    fun setHistoryMinutes(minutes: Int) {
        prefs.edit().putInt("historyMinutes", minutes).apply()
    }

    /** History-window scale for the map slider (day/week/month/all). */
    fun getHistoryScale(): String = prefs.getString("historyScale", DEFAULT_HISTORY_SCALE)!!

    fun setHistoryScale(scale: String) {
        prefs.edit().putString("historyScale", scale).apply()
    }

    /** How long history is kept before auto-prune (day/week/month/all). */
    fun getAutoPruneScale(): String = prefs.getString("autoPruneScale", DEFAULT_AUTO_PRUNE_SCALE)!!

    fun setAutoPruneScale(scale: String) {
        prefs.edit().putString("autoPruneScale", scale).apply()
    }

    /** Whether background MQTT collection is wanted (defaults to on). */
    fun getCollectingEnabled(): Boolean = prefs.getBoolean("collectingEnabled", true)

    fun setCollectingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("collectingEnabled", enabled).apply()
    }

    /**
     * The satellite object-count cache was written by a broken YOLO decode
     * (thousands of phantom swimming pools per drone). It is purged exactly
     * once after the fix so old corrupt counts never resurface.
     */
    fun isSatelliteCachePurged(): Boolean =
        prefs.getBoolean("satelliteCachePurgedV2", false)

    fun markSatelliteCachePurged() {
        prefs.edit().putBoolean("satelliteCachePurgedV2", true).apply()
    }

    companion object {
        const val DEFAULT_HISTORY_MINUTES = 30
        const val DEFAULT_HISTORY_SCALE = "day"
        const val DEFAULT_AUTO_PRUNE_SCALE = HISTORY_SCALE_ALL
        const val DEFAULT_BROKER = "65604cba457d4f8992aefe5820219ae4.s1.eu.hivemq.cloud"
        const val DEFAULT_PORT = 8883
        const val DEFAULT_TLS = true
        const val DEFAULT_USER = "skyspy"
        const val DEFAULT_PASS = "skyspyaware"
        const val DEFAULT_TOPIC = "skyspy"
    }
}
