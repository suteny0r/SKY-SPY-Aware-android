package com.suteny0r.skyspyaware

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * MQTT subscriber wrapping Eclipse Paho. Subscribes to <topic>/raw and hands
 * each non-blank line to [onLine]. Auto-reconnects any time the connection is
 * lost (network changes, device sleep, broker restart): a watchdog loop plus an
 * immediate reconnect attempt on [MqttCallback.connectionLost] keeps the app
 * connected as long as [enabled] is true.
 */
class MqttManager(private val context: Context) {

    @Volatile
    private var client: MqttClient? = null
    @Volatile
    private var settings: MqttSettings? = null
    private var onStatus: ((String) -> Unit)? = null
    private val connectedFlag = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    var onLine: ((String) -> Unit)? = null

    fun connect(settings: MqttSettings, onStatus: (String) -> Unit) {
        this.settings = settings
        this.onStatus = onStatus
        enabled.set(true)
        // Watchdog: reconnects if the connection silently dies (e.g. sleep).
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (enabled.get()) {
                delay(WATCHDOG_MS)
                if (enabled.get() && !connectedFlag.get() && !connecting.get()) {
                    startConnect(settings)
                }
            }
        }
        startConnect(settings)
    }

    private fun startConnect(settings: MqttSettings) {
        if (!connecting.compareAndSet(false, true)) return
        scope.launch {
            try {
                connectedFlag.set(false)
                val stale = client
                client = null
                runCatching { stale?.disconnect() }
                runCatching { stale?.close() }

                val scheme = if (settings.tls) "ssl" else "tcp"
                val uri = URI("$scheme://${settings.broker}:${settings.port}")
                val clientId = "skyspy-aware-" +
                        android.os.Process.myPid() + "-" + (0..9999).random()
                val c = MqttClient(uri.toString(), clientId, MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 10
                    if (settings.user.isNotBlank()) userName = settings.user
                    if (settings.pass.isNotBlank()) password = settings.pass.toCharArray()
                    if (settings.tls) socketFactory = SSLSocketFactory.getDefault()
                }
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        connectedFlag.set(false)
                        onStatus?.invoke("Disconnected: ${cause?.message ?: "connection lost"}")
                        if (enabled.get()) startConnect(settings)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val line = String(message?.payload ?: ByteArray(0)).trim()
                        if (line.isNotEmpty()) onLine?.invoke(line)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                this@MqttManager.client = c
                c.connect(opts)
                connectedFlag.set(true)
                val rawTopic = settings.topic.trim('/') + "/raw"
                c.subscribe(rawTopic, 0)
                onStatus?.invoke("Connected to ${settings.broker}:${settings.port} | subscribed $rawTopic")
            } catch (e: Exception) {
                connectedFlag.set(false)
                if (enabled.get()) onStatus?.invoke("Reconnect failed: ${e.message}; retrying")
            } finally {
                connecting.set(false)
            }
        }
    }

    fun disconnect() {
        enabled.set(false)
        connecting.set(false)
        watchdogJob?.cancel()
        val c = client
        client = null
        runCatching { c?.disconnect() }
        runCatching { c?.close() }
        connectedFlag.set(false)
    }

    fun isConnected(): Boolean = connectedFlag.get()

    companion object {
        private const val WATCHDOG_MS = 8000L
    }
}
