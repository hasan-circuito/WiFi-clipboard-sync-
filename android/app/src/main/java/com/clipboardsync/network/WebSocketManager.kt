package com.clipboardsync.network

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class WebSocketManager(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onClipboardReceived: (text: String, hash: String) -> Unit
) {
    companion object {
        private const val TAG = "WebSocketManager"
    }

    private var client: WebSocketClient? = null
    private val gson = Gson()
    private var isIntentionalClose = false

    @Volatile
    var isConnecting: Boolean = false
        private set

    val isConnected: Boolean
        get() = client?.isOpen == true

    @Synchronized
    fun connect(ip: String, port: Int) {
        if (isConnected || isConnecting) {
            Log.d(TAG, "Already connected or connection attempt in progress.")
            return
        }

        disconnect()

        val uri = URI("ws://$ip:$port")
        isIntentionalClose = false
        isConnecting = true

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isConnecting = false
                Log.i(TAG, "Connected to WebSocket server at $uri")
                // Send handshake from Android
                val handshakePayload = mapOf(
                    "type" to "handshake",
                    "device" to "android",
                    "name" to Build.MODEL,
                    "timestamp" to System.currentTimeMillis()
                )
                try {
                    send(gson.toJson(handshakePayload))
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending handshake: ${e.message}")
                }
                onConnected()
            }

            override fun onMessage(message: String?) {
                if (message.isNullOrEmpty()) return
                try {
                    val json = gson.fromJson(message, JsonObject::class.java)
                    val type = json.get("type")?.asString

                    if (type == "clipboard") {
                        val text = json.get("text")?.asString ?: ""
                        val hash = json.get("hash")?.asString ?: ""
                        if (text.isNotEmpty()) {
                            onClipboardReceived(text, hash)
                        }
                    } else if (type == "ping") {
                        val pong = mapOf("type" to "pong")
                        send(gson.toJson(pong))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming message: ${e.message}")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isConnecting = false
                Log.w(TAG, "Connection closed. Code: $code, Reason: $reason, Remote: $remote")
                if (!isIntentionalClose) {
                    onDisconnected()
                }
            }

            override fun onError(ex: Exception?) {
                isConnecting = false
                Log.e(TAG, "WebSocket error: ${ex?.message}")
            }
        }.apply {
            connectionLostTimeout = 10
            connect()
        }
    }

    fun sendClipboard(text: String, hash: String): Boolean {
        if (!isConnected) return false
        return try {
            val payload = mapOf(
                "type" to "clipboard",
                "text" to text,
                "hash" to hash,
                "sender" to "android",
                "timestamp" to System.currentTimeMillis()
            )
            client?.send(gson.toJson(payload))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clipboard payload: ${e.message}")
            false
        }
    }

    @Synchronized
    fun disconnect() {
        isConnecting = false
        isIntentionalClose = true
        try {
            client?.close()
        } catch (e: Exception) {
            // Ignore
        }
        client = null
    }
}
