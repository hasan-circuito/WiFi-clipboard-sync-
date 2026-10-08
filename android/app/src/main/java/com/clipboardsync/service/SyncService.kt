package com.clipboardsync.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipboardsync.network.UdpDiscoveryManager
import com.clipboardsync.network.WebSocketManager
import com.clipboardsync.ui.MainActivity
import java.security.MessageDigest

class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_ID = "clipboard_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "wifi_clip_prefs"
        private const val KEY_LAST_IP = "last_server_ip"
        private const val KEY_LAST_PORT = "last_server_port"

        @Volatile
        var instance: SyncService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var serverIp: String? = null
            private set

        @Volatile
        var lastReceivedFromPcHash: String? = null

        @Volatile
        var lastReceivedFromPcTime: Long = 0

        @Volatile
        var lastSentToPcHash: String? = null

        @Volatile
        var lastSentToPcTime: Long = 0

        var lastReceivedHash: String?
            get() = lastReceivedFromPcHash
            set(value) {
                lastReceivedFromPcHash = value
                lastReceivedFromPcTime = System.currentTimeMillis()
            }

        var onStateChangedListener: (() -> Unit)? = null

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        fun ensureStarted(context: Context) {
            try {
                val intent = Intent(context, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SyncService: ${e.message}")
            }
        }
    }

    private var discoveryManager: UdpDiscoveryManager? = null
    private var webSocketManager: WebSocketManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var pendingClipboardText: String? = null
    private var pendingClipboardHash: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        val initialNotif = buildNotification("Searching for PC on Wi-Fi...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        initNetworking()
        registerNetworkCallback()
    }

    private fun initNetworking() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastIp = prefs.getString(KEY_LAST_IP, null)
        val lastPort = prefs.getInt(KEY_LAST_PORT, 52526)

        webSocketManager = WebSocketManager(
            onConnected = {
                isConnected = true
                updateNotification("Connected to PC ($serverIp)")
                onStateChangedListener?.invoke()

                // Save successful IP for fast direct reconnects
                serverIp?.let { ip ->
                    prefs.edit()
                        .putString(KEY_LAST_IP, ip)
                        .putInt(KEY_LAST_PORT, 52526)
                        .apply()
                }

                // Stop UDP multicast discovery while connected to save battery
                discoveryManager?.stop()

                // Flush any clipboard copied while offline
                pendingClipboardText?.let { text ->
                    val hash = pendingClipboardHash ?: sha256(text)
                    webSocketManager?.sendClipboard(text, hash)
                    pendingClipboardText = null
                    pendingClipboardHash = null
                    Log.i(TAG, "Flushed offline clipboard copy to PC.")
                }
            },
            onDisconnected = {
                isConnected = false
                updateNotification("Disconnected. Searching on Wi-Fi...")
                onStateChangedListener?.invoke()
                // Resume discovery when disconnected
                discoveryManager?.start()
            },
            onClipboardReceived = { text, hash ->
                Log.d(TAG, "Received clipboard text from PC: ${text.take(30)}...")
                lastReceivedFromPcHash = hash
                lastReceivedFromPcTime = System.currentTimeMillis()

                val preview = if (text.length > 30) text.take(27) + "..." else text
                updateNotification("📋 Synced: \"$preview\"")

                // Update clipboard via AccessibilityService or fallback to direct manager
                val aService = ClipboardAccessibilityService.instance
                if (aService != null) {
                    aService.setClipboardText(text)
                } else {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("WiFiSync", text))
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback direct clipboard set: ${e.message}")
                    }
                }

                onStateChangedListener?.invoke()
            }
        )

        // Fast connection to last known PC IP before waiting for UDP broadcast
        if (!lastIp.isNullOrEmpty()) {
            serverIp = lastIp
            Log.i(TAG, "Initiating fast direct connection to last known PC: $lastIp:$lastPort")
            webSocketManager?.connect(lastIp, lastPort)
        }

        discoveryManager = UdpDiscoveryManager(this) { ip, port, name ->
            val ws = webSocketManager
            if (!isConnected && ws?.isConnecting != true) {
                serverIp = ip
                Log.i(TAG, "Connecting to discovered PC: $name at $ip:$port")
                ws?.connect(ip, port)
            }
        }
        discoveryManager?.start()
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Wi-Fi network connected. Starting auto-discovery.")
                    if (!isConnected) {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val lastIp = prefs.getString(KEY_LAST_IP, null)
                        val lastPort = prefs.getInt(KEY_LAST_PORT, 52526)
                        if (!lastIp.isNullOrEmpty()) {
                            serverIp = lastIp
                            webSocketManager?.connect(lastIp, lastPort)
                        }
                        discoveryManager?.start()
                    }
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Wi-Fi network disconnected.")
                    webSocketManager?.disconnect()
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun sendClipboardFromLocal(text: String): Boolean {
        if (text.isEmpty()) return false
        val hash = sha256(text)
        val now = System.currentTimeMillis()

        // 1. Suppress echo if this matches text received from PC within 15 seconds
        if (hash == lastReceivedFromPcHash && (now - lastReceivedFromPcTime < 15000)) {
            Log.d(TAG, "Suppressed local send: echo from PC.")
            return false
        }

        // 2. Suppress duplicate rapid re-sends of identical content within 1.2 seconds
        if (hash == lastSentToPcHash && (now - lastSentToPcTime < 1200)) {
            Log.d(TAG, "Suppressed duplicate local send within 1.2s.")
            return false
        }

        lastSentToPcHash = hash
        lastSentToPcTime = now

        if (isConnected) {
            val sent = webSocketManager?.sendClipboard(text, hash) ?: false
            if (sent) {
                val preview = if (text.length > 25) text.take(22) + "..." else text
                updateNotification("📤 Sent to Laptop: \"$preview\"")
                Log.i(TAG, "Successfully sent clipboard to PC (${text.length} chars)")
                return true
            }
        } else {
            // Buffer to send as soon as connection is established
            pendingClipboardText = text
            pendingClipboardHash = hash
            updateNotification("📋 Queued for Laptop (${text.length} chars)")
            Log.d(TAG, "Buffered local clipboard copy until PC connects.")
            return true
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wi-Fi Clipboard Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Wi-Fi clipboard synchronization active in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val captureIntent = Intent(this, ClipboardCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val capturePendingIntent = PendingIntent.getActivity(
            this,
            1,
            captureIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi Clipboard Sync")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_send,
                "📤 Push to Laptop",
                capturePendingIntent
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignore
        }
        discoveryManager?.stop()
        webSocketManager?.disconnect()
        instance = null
        isConnected = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
