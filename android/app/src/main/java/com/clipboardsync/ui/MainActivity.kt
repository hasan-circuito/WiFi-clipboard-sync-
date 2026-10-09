package com.clipboardsync.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
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
        binding.orbView.resume()
        updateUiState()
        checkPendingInstallState()

        SyncService.onStateChangedListener = {
            runOnUiThread { updateUiState() }
        }
        SyncService.onMessageSyncedListener = { _, _ ->
            runOnUiThread {
                binding.orbView.triggerSync()
                performHaptic()
            }
        }
        checkAndSyncForegroundClipboard()
    }

    override fun onPause() {
        super.onPause()
        binding.orbView.pause()
        SyncService.onStateChangedListener = null
        SyncService.onMessageSyncedListener = null
    }

    private fun performHaptic() {
        try {
            binding.root.isHapticFeedbackEnabled = true
            binding.root.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } catch (e: Exception) {
            try {
                binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (ignored: Exception) {}
        }
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
        // Floating Input character counter
        binding.etTestText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvCharCount.text = "${s?.length ?: 0} chars"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Step 1: Accessibility
        binding.btnEnableAccessibility.setOnClickListener {
            performHaptic()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Wi-Fi Clipboard Sync' and turn it ON", Toast.LENGTH_LONG).show()
        }

        // Step 2: Overlay Permission
        binding.btnEnableOverlay.setOnClickListener {
            performHaptic()
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

        // Step 3: Battery Optimization Exemption
        binding.btnBatteryOptimization.setOnClickListener {
            performHaptic()
            requestIgnoreBatteryOptimization()
        }

        // Tactile Push Button
        binding.btnSendTest.setOnClickListener {
            performHaptic()
            val text = binding.etTestText.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                if (SyncService.isConnected) {
                    binding.orbView.triggerSync()
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
                                binding.orbView.triggerSync()
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
            performHaptic()
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
                performHaptic()
                UpdateManager.installApk(this, downloadedFile)
            }
        } else {
            binding.btnUpdateNow.text = "Update Now"
            binding.btnUpdateNow.setOnClickListener {
                performHaptic()
                startUpdateDownload(info)
            }
        }

        binding.btnDismissUpdate.setOnClickListener {
            performHaptic()
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
                performHaptic()
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
                    performHaptic()
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
        val emeraldColor = ContextCompat.getColor(this, R.color.accent_emerald)
        val amberColor = ContextCompat.getColor(this, R.color.accent_amber)
        val cyanColor = ContextCompat.getColor(this, R.color.accent_cyan)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        val primaryColor = ContextCompat.getColor(this, R.color.primary)

        // 1. Connection state & Luminescent Orb
        if (SyncService.isConnected) {
            binding.orbView.setState(LuminescentOrbView.State.CONNECTED)
            binding.tvConnectionStatus.text = "🟢 Connected to Laptop"
            binding.tvConnectionStatus.setTextColor(emeraldColor)
            binding.tvServerDetails.text = "Server IP: ${SyncService.serverIp ?: "Unknown"}"
        } else {
            binding.orbView.setState(LuminescentOrbView.State.SEARCHING)
            binding.tvConnectionStatus.text = "🟡 Searching on Wi-Fi..."
            binding.tvConnectionStatus.setTextColor(amberColor)
            binding.tvServerDetails.text = "Listening for discovery beacon on UDP port 52525"
        }

        // 2. Accessibility state
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (accessibilityEnabled) {
            binding.tvAccessibilityStatus.text = "🟢 Service Active • Background Sync Ready"
            binding.tvAccessibilityStatus.setTextColor(emeraldColor)
            binding.btnEnableAccessibility.text = "✓ Configured & Active"
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(0xFF132F27.toInt())
            binding.btnEnableAccessibility.setTextColor(emeraldColor)
            binding.tvStep1Badge.text = "✓ Active"
            binding.tvStep1Badge.setTextColor(emeraldColor)
            binding.tvStep1Badge.setBackgroundResource(R.drawable.bg_badge_emerald_pill)
            val padH = (8 * resources.displayMetrics.density).toInt()
            val padV = (3 * resources.displayMetrics.density).toInt()
            binding.tvStep1Badge.setPadding(padH, padV, padH, padV)
        } else {
            binding.tvAccessibilityStatus.text = "🔴 Accessibility Service: Disabled"
            binding.tvAccessibilityStatus.setTextColor(amberColor)
            binding.btnEnableAccessibility.text = "Enable in Accessibility Settings"
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnEnableAccessibility.backgroundTintList = ColorStateList.valueOf(cyanColor)
            binding.btnEnableAccessibility.setTextColor(primaryColor)
            binding.tvStep1Badge.text = "Required"
            binding.tvStep1Badge.setTextColor(mutedColor)
            binding.tvStep1Badge.background = null
            binding.tvStep1Badge.setPadding(0, 0, 0, 0)
        }

        // 3. Display Over Other Apps (Overlay) state
        val overlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (overlayEnabled) {
            binding.tvOverlayStatus.text = "🟢 Universal Sync (Overlay): Active"
            binding.tvOverlayStatus.setTextColor(emeraldColor)
            binding.btnEnableOverlay.text = "✓ Permission Granted"
            binding.btnEnableOverlay.isEnabled = false
            binding.btnEnableOverlay.backgroundTintList = ColorStateList.valueOf(0xFF132F27.toInt())
            binding.btnEnableOverlay.setTextColor(emeraldColor)
            binding.tvStep2Badge.text = "✓ Active"
            binding.tvStep2Badge.setTextColor(emeraldColor)
            binding.tvStep2Badge.setBackgroundResource(R.drawable.bg_badge_emerald_pill)
            val padH = (8 * resources.displayMetrics.density).toInt()
            val padV = (3 * resources.displayMetrics.density).toInt()
            binding.tvStep2Badge.setPadding(padH, padV, padH, padV)
        } else {
            binding.tvOverlayStatus.text = "🔴 Universal Sync: Permission Required"
            binding.tvOverlayStatus.setTextColor(amberColor)
            binding.btnEnableOverlay.text = "Allow Display Over Other Apps"
            binding.btnEnableOverlay.isEnabled = true
            binding.btnEnableOverlay.backgroundTintList = ColorStateList.valueOf(cyanColor)
            binding.btnEnableOverlay.setTextColor(primaryColor)
            binding.tvStep2Badge.text = "Universal Copy"
            binding.tvStep2Badge.setTextColor(mutedColor)
            binding.tvStep2Badge.background = null
            binding.tvStep2Badge.setPadding(0, 0, 0, 0)
        }

        // 4. Battery Optimization state
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        if (isIgnoringBattery) {
            binding.tvBatteryStatus.text = "🟢 Unrestricted background execution active"
            binding.tvBatteryStatus.setTextColor(emeraldColor)
            binding.btnBatteryOptimization.isEnabled = false
            binding.btnBatteryOptimization.text = "✓ Battery Exemption Active"
            binding.btnBatteryOptimization.backgroundTintList = ColorStateList.valueOf(0xFF132F27.toInt())
            binding.btnBatteryOptimization.setTextColor(emeraldColor)
            binding.tvStep3Badge.text = "✓ Active"
            binding.tvStep3Badge.setTextColor(emeraldColor)
            binding.tvStep3Badge.setBackgroundResource(R.drawable.bg_badge_emerald_pill)
            val padH = (8 * resources.displayMetrics.density).toInt()
            val padV = (3 * resources.displayMetrics.density).toInt()
            binding.tvStep3Badge.setPadding(padH, padV, padH, padV)
        } else {
            binding.btnBatteryOptimization.isEnabled = true
            binding.btnBatteryOptimization.text = "Allow Unrestricted Background"
            binding.btnBatteryOptimization.backgroundTintList = ColorStateList.valueOf(0xFF1F2438.toInt())
            binding.btnBatteryOptimization.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvStep3Badge.text = "Keep-Alive"
            binding.tvStep3Badge.setTextColor(mutedColor)
            binding.tvStep3Badge.background = null
            binding.tvStep3Badge.setPadding(0, 0, 0, 0)
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
