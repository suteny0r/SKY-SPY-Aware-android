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
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttSecurityException
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
                if (enabled.get() && !isConnected() && !connecting.get()) {
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
                if (!enabled.get()) {
                    // Disconnect was requested while this attempt was in
                    // flight; don't resurrect the connection.
                    runCatching { c.disconnect() }
                    runCatching { c.close() }
                    if (this@MqttManager.client === c) this@MqttManager.client = null
                    return@launch
                }
                connectedFlag.set(true)
                val rawTopic = settings.topic.trim('/') + "/raw"
                c.subscribe(rawTopic, 0)
                onStatus?.invoke("Connected to ${settings.broker}:${settings.port} | subscribed $rawTopic")
            } catch (e: Exception) {
                connectedFlag.set(false)
                runCatching { this@MqttManager.client?.disconnect() }
                if (enabled.get()) {
                    if (isTransient(e)) {
                        // Network unreachable, timeouts, broker temporarily
                        // unavailable: keep retrying via the watchdog.
                        onStatus?.invoke("Connection unavailable: ${e.message}; retrying")
                    } else {
                        // Broker actively rejected us (bad credentials, not
                        // authorized, bad protocol/client id): retrying won't
                        // help, so stop. User can reconnect from Settings.
                        enabled.set(false)
                        onStatus?.invoke("Server rejected connection: ${e.message} (not retrying)")
                    }
                }
            } finally {
                connecting.set(false)
            }
        }
    }

    fun disconnect() {
        enabled.set(false)
        // Do NOT clear `connecting` here: an in-flight startConnect owns that
        // guard and releases it in its own finally. Clearing it early lets a
        // quick Disconnect->Connect run two attempts concurrently.
        watchdogJob?.cancel()
        val c = client
        client = null
        runCatching { c?.disconnect() }
        runCatching { c?.close() }
        connectedFlag.set(false)
    }

    // The cached flag can stay stale-true after Doze kills the socket without
    // a connectionLost callback; trust it only when the client agrees.
    fun isConnected(): Boolean =
        connectedFlag.get() && client?.isConnected == true

    /**
     * True if a connect failure is worth retrying. Network-level problems
     * (unreachable host, timeouts, SSL, server temporarily unavailable) are
     * transient. A broker that actively rejects the connection (bad user or
     * password, not authorized, unacceptable protocol version, rejected
     * client id) will keep failing, so we stop retrying instead.
     */
    private fun isTransient(e: Throwable): Boolean {
        // Broker-side rejections surface as MqttSecurityException or an
        // MqttException with the CONNACK reason code.
        if (e is MqttSecurityException) return false
        if (e is MqttException) {
            when (e.reasonCode) {
                MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt(),
                MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt(),
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
                MqttException.REASON_CODE_SUBSCRIBE_FAILED.toInt() -> return false
            }
        }
        // Everything else (timeouts, unreachable, DNS, SSL, server
        // unavailable, connection lost) is transient.
        return true
    }

    companion object {
        private const val WATCHDOG_MS = 8000L
    }
}
