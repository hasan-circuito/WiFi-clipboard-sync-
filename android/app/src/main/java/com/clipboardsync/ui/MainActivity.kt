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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clipboardsync.R
import com.clipboardsync.databinding.ActivityMainBinding
import com.clipboardsync.service.ClipboardAccessibilityService
import com.clipboardsync.service.SyncService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        startSyncService()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
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

        // 3. Battery Optimization state
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
