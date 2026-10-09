package com.clipboardsync.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clipboardsync.R
import com.clipboardsync.databinding.ActivityMainBinding
import com.clipboardsync.service.ClipboardAccessibilityService
import com.clipboardsync.service.SyncService
import com.clipboardsync.update.UpdateInfo
import com.clipboardsync.update.UpdateManager
import com.clipboardsync.update.UpdateResult
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var downloadedApkFile: java.io.File? = null
    private var availableUpdateInfo: UpdateInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        startSyncService()
        setupListeners()
        checkForUpdates(silent = true)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        checkPendingInstallState()
        SyncService.onStateChangedListener = {
            runOnUiThread { updateUiState() }
        }
        checkAndSyncForegroundClipboard()
    }

    override fun onPause() {
        super.onPause()
        SyncService.onStateChangedListener = null
    }

    private fun checkAndSyncForegroundClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString() ?: item.coerceToText(this)?.toString()
                if (!text.isNullOrEmpty() && SyncService.isConnected) {
                    SyncService.instance?.sendClipboardFromLocal(text)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startSyncService() {
        val serviceIntent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupListeners() {
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Wi-Fi Clipboard Sync' and turn it ON", Toast.LENGTH_LONG).show()
        }

        binding.btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "Turn ON 'Allow display over other apps' for Wi-Fi Clipboard Sync", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        startActivity(fallback)
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Could not open overlay settings", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Not required for your Android version", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.btnSendTest.setOnClickListener {
            val text = binding.etTestText.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                if (SyncService.isConnected) {
                    SyncService.instance?.sendClipboardFromLocal(text)
                    Toast.makeText(this, "Sent to PC!", Toast.LENGTH_SHORT).show()
                    binding.etTestText.text?.clear()
                } else {
                    Toast.makeText(this, "Not connected to PC yet. Ensure both are on same Wi-Fi.", Toast.LENGTH_LONG).show()
                }
            } else {
                // If input field is empty, send whatever is on the device clipboard
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val item = clip.getItemAt(0)
                        val clipText = item.text?.toString() ?: item.coerceToText(this)?.toString()
                        if (!clipText.isNullOrEmpty()) {
                            if (SyncService.isConnected) {
                                SyncService.instance?.sendClipboardFromLocal(clipText)
                                Toast.makeText(this, "Sent device clipboard to PC! (${clipText.length} chars)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Not connected to PC yet.", Toast.LENGTH_LONG).show()
                            }
                            return@setOnClickListener
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                Toast.makeText(this, "Please enter some text or copy to clipboard first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckUpdates.setOnClickListener {
            checkForUpdates(silent = false)
        }
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            when (val result = UpdateManager.checkForUpdate(this@MainActivity)) {
                is UpdateResult.UpdateAvailable -> {
                    showUpdateBanner(result.info)
                }
                is UpdateResult.UpToDate -> {
                    if (!silent) {
                        Toast.makeText(
                            this@MainActivity,
                            "App is up to date (v${result.currentVersion})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is UpdateResult.Error -> {
                    if (!silent) {
                        Toast.makeText(
                            this@MainActivity,
                            "Update check: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showUpdateBanner(info: UpdateInfo) {
        availableUpdateInfo = info
        binding.cardUpdateBanner.visibility = View.VISIBLE
        binding.tvUpdateTitle.text = "🚀 Update Available: v${info.versionName}"
        val notes = info.changelog?.trim() ?: "A new version of Wi-Fi Clipboard Sync is available."
        binding.tvUpdateMessage.text = notes
        binding.pbUpdateProgress.visibility = View.GONE
        binding.tvUpdateProgress.visibility = View.GONE
        binding.btnUpdateNow.isEnabled = true
        binding.btnDismissUpdate.isEnabled = true

        val downloadedFile = downloadedApkFile
        if (downloadedFile != null && downloadedFile.exists()) {
            binding.btnUpdateNow.text = "Install Now"
            binding.btnUpdateNow.setOnClickListener {
                UpdateManager.installApk(this, downloadedFile)
            }
        } else {
            binding.btnUpdateNow.text = "Update Now"
            binding.btnUpdateNow.setOnClickListener {
                startUpdateDownload(info)
            }
        }

        binding.btnDismissUpdate.setOnClickListener {
            binding.cardUpdateBanner.visibility = View.GONE
        }
    }

    private fun checkPendingInstallState() {
        val file = downloadedApkFile
        if (file != null && file.exists()) {
            binding.cardUpdateBanner.visibility = View.VISIBLE
            binding.btnUpdateNow.isEnabled = true
            binding.btnDismissUpdate.isEnabled = true
            binding.btnUpdateNow.text = "Install Now"
            binding.tvUpdateProgress.text = "Update downloaded. Ready to install."
            binding.tvUpdateProgress.visibility = View.VISIBLE
            binding.pbUpdateProgress.visibility = View.GONE
            binding.btnUpdateNow.setOnClickListener {
                UpdateManager.installApk(this, file)
            }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        binding.btnUpdateNow.isEnabled = false
        binding.btnDismissUpdate.isEnabled = false
        binding.pbUpdateProgress.visibility = View.VISIBLE
        binding.tvUpdateProgress.visibility = View.VISIBLE
        binding.pbUpdateProgress.progress = 0
        binding.tvUpdateProgress.text = "Starting download..."

        lifecycleScope.launch {
            try {
                val apkFile = UpdateManager.downloadApk(this@MainActivity, info.apkUrl) { progress ->
                    binding.pbUpdateProgress.progress = progress
                    binding.tvUpdateProgress.text = "Downloading: $progress%"
                }

                downloadedApkFile = apkFile
                binding.tvUpdateProgress.text = "Download complete. Ready to install."
                binding.btnUpdateNow.isEnabled = true
                binding.btnDismissUpdate.isEnabled = true
                binding.btnUpdateNow.text = "Install Now"
                binding.btnUpdateNow.setOnClickListener {
                    UpdateManager.installApk(this@MainActivity, apkFile)
                }

                UpdateManager.installApk(this@MainActivity, apkFile)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                binding.btnUpdateNow.isEnabled = true
                binding.btnDismissUpdate.isEnabled = true
                binding.btnUpdateNow.text = "Update Now"
                binding.pbUpdateProgress.visibility = View.GONE
                binding.tvUpdateProgress.visibility = View.GONE
            }
        }
    }

    private fun updateUiState() {
        // 1. Connection state
        if (SyncService.isConnected) {
            binding.tvConnectionStatus.text = "🟢 Connected to Laptop"
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvServerDetails.text = "Server IP: ${SyncService.serverIp}"
        } else {
            binding.tvConnectionStatus.text = "🟡 Searching on Wi-Fi..."
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.tvServerDetails.text = "Listening for discovery beacon on UDP port 52525"
        }

        // 2. Accessibility state
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (accessibilityEnabled) {
            binding.tvAccessibilityStatus.text = "🟢 Accessibility Service: Active"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnEnableAccessibility.text = "Accessibility Enabled (Ready)"
            binding.btnEnableAccessibility.isEnabled = false
        } else {
            binding.tvAccessibilityStatus.text = "🔴 Accessibility Service: Disabled"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.btnEnableAccessibility.text = "Enable in Accessibility Settings"
            binding.btnEnableAccessibility.isEnabled = true
        }

        // 3. Display Over Other Apps (Overlay) state
        val overlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (overlayEnabled) {
            binding.tvOverlayStatus.text = "🟢 Universal Sync (Overlay): Active"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnEnableOverlay.text = "Overlay Permission Granted (Ready)"
            binding.btnEnableOverlay.isEnabled = false
        } else {
            binding.tvOverlayStatus.text = "🔴 Universal Sync: Permission Required"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.btnEnableOverlay.text = "Allow Display Over Other Apps"
            binding.btnEnableOverlay.isEnabled = true
        }

        // 4. Battery Optimization state
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        if (isIgnoringBattery) {
            binding.tvBatteryStatus.text = "🟢 Unrestricted background execution active"
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnBatteryOptimization.isEnabled = false
            binding.btnBatteryOptimization.text = "Battery Optimization Disabled"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (ClipboardAccessibilityService.isRunning) return true

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null &&
                enabledComponent.packageName == packageName &&
                enabledComponent.className.contains("ClipboardAccessibilityService")
            ) {
                return true
            }
        }
        return false
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}
