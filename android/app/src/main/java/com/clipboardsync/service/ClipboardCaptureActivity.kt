package com.clipboardsync.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardCapture"
    }

    private val hasCaptured = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ClipboardAccessibilityService.isCameraActive()) {
            Log.i(TAG, "Camera is active; aborting ClipboardCaptureActivity to avoid focus stealing.")
            closeActivity()
            return
        }
        SyncService.ensureStarted(this)

        // Critical: Do NOT dismiss soft keyboard / IME
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val method = Activity::class.java.getMethod("setAllowCrossUidActivitySwitchFromBelow", Boolean::class.javaPrimitiveType)
                method.invoke(this, true)
            } catch (_: Throwable) {}
        }

        // Attach a focusable transparent view so WindowManager rapidly focuses this window
        val transparentView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(transparentView)
        transparentView.requestFocus()

        // Immediate check in case window focus is granted on create
        handler.postDelayed({
            if (!hasCaptured.get() && !isFinishing) {
                captureAndFinish(0)
            }
        }, 40)

        // Safety fallback: if focus change never fires within 800ms, close cleanly
        handler.postDelayed({
            if (!hasCaptured.get() && !isFinishing) {
                closeActivity()
            }
        }, 800)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hasCaptured.set(false)
        handler.postDelayed({
            captureAndFinish(0)
        }, 20)
    }

    override fun onResume() {
        super.onResume()
        if (hasWindowFocus() && !hasCaptured.get()) {
            handler.postDelayed({
                captureAndFinish(0)
            }, 20)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasCaptured.get()) {
            // Android 10+ requires true window focus before getPrimaryClip returns non-null.
            handler.postDelayed({
                captureAndFinish(0)
            }, 20)
        }
    }

    private fun captureAndFinish(retryCount: Int = 0) {
        if (isFinishing || isDestroyed) return
        if (hasCaptured.get() && retryCount == 0) return

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            var text: String? = null

            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                text = item.text?.toString() ?: item.coerceToText(this)?.toString()
            }

            if (!text.isNullOrEmpty()) {
                if (hasCaptured.compareAndSet(false, true)) {
                    ClipboardAccessibilityService.instance?.updateLastHandled(text)
                    val sync = SyncService.instance
                    if (sync != null) {
                        val sent = sync.sendClipboardFromLocal(text)
                        if (sent) {
                            Toast.makeText(
                                applicationContext,
                                "📋 Sent to Laptop (${text.length} chars)",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.i(TAG, "Successfully captured foreground clipboard: ${text.take(30)}...")
                        }
                    }
                }
                closeActivity()
            } else if (retryCount < 8) {
                // Retry if focus state was just delivered and clipboard IPC is settling (8 * 50ms = 400ms)
                handler.postDelayed({
                    captureAndFinish(retryCount + 1)
                }, 50)
            } else {
                closeActivity()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing clipboard: ${e.message}")
            closeActivity()
        }
    }

    private fun closeActivity() {
        if (!isFinishing) {
            finish()
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
