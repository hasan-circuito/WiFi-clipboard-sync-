package com.clipboardsync.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpDiscoveryManager(
    private val context: Context,
    private val onServerDiscovered: (ip: String, port: Int, name: String) -> Unit
) {
    companion object {
        private const val TAG = "UdpDiscoveryManager"
        private const val DISCOVERY_PORT = 52525
        private const val BUFFER_SIZE = 2048
    }

    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null
    private val gson = Gson()

    fun start() {
        if (job?.isActive == true) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("WiFiClipboardSyncMulticastLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 2000
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind to port $DISCOVERY_PORT, fallback to ephemeral port: ${e.message}")
                try {
                    socket = DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 2000
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to create DatagramSocket: ${e2.message}")
                    return@launch
                }
            }

            // Launch concurrent sender probe coroutine
            launch {
                sendDiscoveryProbes()
            }

            // Listener loop
            val buffer = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket?.receive(packet)
                    val rawJson = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val json = gson.fromJson(rawJson, JsonObject::class.java)

                    if (json.has("service") && json.get("service").asString == "clip-sync") {
                        val ip = json.get("ip").asString
                        val port = if (json.has("port")) json.get("port").asInt else 52526
                        val name = if (json.has("name")) json.get("name").asString else "Laptop"

                        Log.d(TAG, "Discovered server: $name at $ip:$port")
                        withContext(Dispatchers.Main) {
                            onServerDiscovered(ip, port, name)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout is expected, allows checking isActive
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Error receiving UDP packet: ${e.message}")
                        delay(500)
                    }
                }
            }
        }
    }

    private suspend fun sendDiscoveryProbes() {
        val probePayload = gson.toJson(
            mapOf("service" to "clip-sync-discover")
        ).toByteArray(Charsets.UTF_8)

        while (job?.isActive == true) {
            try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val probePacket = DatagramPacket(probePayload, probePayload.size, broadcastAddr, DISCOVERY_PORT)
                socket?.send(probePacket)
            } catch (e: Exception) {
                Log.d(TAG, "Probe broadcast: ${e.message}")
            }
            delay(3000)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        multicastLock = null
    }
}
