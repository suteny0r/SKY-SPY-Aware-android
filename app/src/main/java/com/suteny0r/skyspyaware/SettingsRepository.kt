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

    companion object {
        const val DEFAULT_BROKER = "65604cba457d4f8992aefe5820219ae4.s1.eu.hivemq.cloud"
        const val DEFAULT_PORT = 8883
        const val DEFAULT_TLS = true
        const val DEFAULT_USER = "skyspy"
        const val DEFAULT_PASS = "skyspyaware"
        const val DEFAULT_TOPIC = "skyspy"
    }
}
