package com.suteny0r.skyspyaware

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * each non-blank line to [onLine]. Default mode of the app: MQTT subscriber.
 */
class MqttManager(private val context: Context) {

    private var client: MqttClient? = null
    private var scope: CoroutineScope? = null
    private val connectedFlag = AtomicBoolean(false)

    var onLine: ((String) -> Unit)? = null

    fun connect(settings: MqttSettings, onStatus: (String) -> Unit) {
        disconnect()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        this.scope = scope
        scope.launch {
            try {
                val scheme = if (settings.tls) "ssl" else "tcp"
                val uri = URI("$scheme://${settings.broker}:${settings.port}")
                val clientId = "skyspy-aware-" +
                        android.os.Process.myPid() + "-" + (0..9999).random()
                val c = MqttClient(
                    uri.toString(), clientId, MemoryPersistence()
                )
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                    if (settings.user.isNotBlank()) userName = settings.user
                    if (settings.pass.isNotBlank()) password = settings.pass.toCharArray()
                    if (settings.tls) socketFactory = SSLSocketFactory.getDefault()
                }
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        connectedFlag.set(false)
                        onStatus("Disconnected: ${cause?.message ?: "connection lost"}")
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
                onStatus("Connected to ${settings.broker}:${settings.port} | subscribed $rawTopic")
            } catch (e: Exception) {
                connectedFlag.set(false)
                onStatus("Error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        val c = client
        client = null
        try {
            c?.disconnect()
            c?.close()
        } catch (_: Exception) {
        }
        scope?.cancel()
        scope = null
        connectedFlag.set(false)
    }

    fun isConnected(): Boolean = connectedFlag.get()
}
