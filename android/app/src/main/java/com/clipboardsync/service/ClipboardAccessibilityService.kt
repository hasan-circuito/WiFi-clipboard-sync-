package com.clipboardsync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipAccessibility"

        @Volatile
        var instance: ClipboardAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        val COPY_KEYWORDS = listOf(
            // English
            "copy", "cut",
            // Bengali (formal AOSP "অনুলিপি" + colloquial "কপি")
            "অনুলিপি", "অনুলিপি করুন", "কপি", "কপি করুন", "কাটুন", "কাট", "কাটা",
            // Hindi
            "कॉपी", "कट", "प्रतिलिपि",
            // Spanish
            "copiar", "cortar",
            // French
            "copier", "couper",
            // German
            "kopieren", "ausschneiden",
            // Arabic
            "نسخ", "قص",
            // Russian
            "копировать", "вырезать",
            // Portuguese
            "copiar", "recortar",
            // Chinese
            "复制", "剪切",
            // Japanese
            "コピー", "切り取り",
            // Korean
            "복사", "잘라내기",
            // Italian
            "copia", "taglia", "copiare",
            // Turkish
            "kopyala", "kes",
            // Indonesian / Malay
            "salin", "potong",
            // Vietnamese
            "sao chép", "cắt"
        )

        fun isKeyboardPackage(packageName: CharSequence?): Boolean {
            if (packageName.isNullOrEmpty()) return false
            val p = packageName.toString().lowercase()
            return p.contains("inputmethod") ||
                    p.contains("keyboard") ||
                    p.contains("swiftkey") ||
                    p.contains("honeyboard") ||
                    p.contains("ridmik") ||
                    p.contains("gboard") ||
                    p.contains("fleksy") ||
                    p.contains("touchpal") ||
                    p.contains("openboard") ||
                    p.contains("anysoftkeyboard") ||
                    p.contains("yandex.keyboard") ||
                    p.matches(Regex(".*([._]ime[._]|\\.ime$|^ime[._]).*"))
        }

        fun isIgnoredPackage(packageName: CharSequence?): Boolean {
            if (packageName.isNullOrEmpty()) return false
            val p = packageName.toString().lowercase()
            return isKeyboardPackage(p) ||
                    p.contains("camera") ||
                    p.contains("snapcam") ||
                    p.contains("launcher") ||
                    p.endsWith(".home") ||
                    p.contains(".home.") ||
                    p == "com.miui.home" ||
                    p == "com.coloros.home" ||
                    p == "com.sec.android.app.camera" ||
                    p == "com.google.android.googlecamera" ||
                    p == "com.android.camera" ||
                    p == "com.sec.android.app.launcher" ||
                    p == "com.google.android.apps.nexuslauncher"
        }

        fun matchesKeyword(str: String?): Boolean {
            if (str.isNullOrBlank()) return false
            val trimmed = str.trim()
            if (trimmed.length > 50) return false // Buttons/actions are short labels, not paragraphs
            val lower = trimmed.lowercase()

            val words = lower.split(Regex("[\\s.,:;!?()\\[\\]\"'-]+")).filter { it.isNotEmpty() }
            for (kw in COPY_KEYWORDS) {
                if (kw.contains(" ")) {
                    if (lower.contains(kw)) return true
                } else {
                    if (words.any { it == kw }) return true
                }
            }

            // Common compound phrases
            if (lower.contains("copy to clipboard") ||
                lower.contains("copy link") ||
                lower.contains("copy code") ||
                lower.contains("copy text") ||
                lower.contains("copy url") ||
                lower.contains("copy address") ||
                lower.contains("copy invite") ||
                lower.contains("copy phone") ||
                lower.contains("copy message") ||
                lower.contains("copy caption") ||
                lower.contains("কপি করুন") ||
                lower.contains("অনুলিপি করুন") ||
                lower.contains("কপি লিংক") ||
                lower.contains("কপি লিঙ্ক") ||
                lower.contains("লিংক কপি") ||
                lower.contains("লিঙ্ক কপি") ||
                lower.contains("মেসেজ কপি") ||
                lower.contains("টেক্সট কপি")
            ) {
                return true
            }

            return false
        }

        fun isCopyViewId(resId: String?): Boolean {
            if (resId.isNullOrBlank()) return false
            val lower = resId.lowercase()

            // 1. Explicitly ignore shortcuts, cutouts, camera components, launchers, execution handlers, and copyright notices
            if (lower.contains("shortcut") || lower.contains("cutout") ||
                lower.contains("camera") || lower.contains("launcher") || lower.contains("execute") ||
                lower.contains("copyright") || lower.contains("copyleft")) {
                return false
            }

            // Extract entry name after any package/id qualifier (e.g. "android:id/copy" -> "copy")
            val entryName = lower.substringAfterLast(":id/").substringAfterLast("/")

            // 2. Copy matching (contains "copy" on entry name)
            if (entryName.contains("copy")) {
                return true
            }

            // 3. Cut matching (strict token boundaries on "cut" to avoid substring collisions)
            val isCut = entryName == "cut" || entryName.startsWith("cut_") || entryName.endsWith("_cut") ||
                    entryName.contains("_cut_") || entryName.contains("menuitem_cut") || entryName.contains("action_cut")

            return isCut
        }

        fun isCopyKeywordOnly(text: String?): Boolean {
            if (text.isNullOrBlank()) return true
            val trimmed = text.trim().lowercase()
            if (COPY_KEYWORDS.any { it == trimmed }) return true
            val commonUiLabels = listOf(
                "share", "cancel", "ok", "select all", "paste", "cut",
                "শেয়ার", "বাতিল", "পেস্ট", "কাট", "clipboard", "ক্লিপবোর্ড",
                "more options", "delete", "মুছুন", "search", "web search"
            )
            return commonUiLabels.any { it == trimmed }
        }

        fun isLikelySystemUiClipboardText(text: String?): Boolean {
            if (text.isNullOrBlank() || text.length < 2) return false
            if (isCopyKeywordOnly(text)) return false
            val lower = text.trim().lowercase()
            if (lower.matches(Regex("\\d{1,2}:\\d{2}(\\s*[ap]m)?"))) return false // time like 10:45
            if (lower.matches(Regex("\\d{1,3}%"))) return false // battery percentage like 85%
            if (lower == "quick settings" || lower == "notifications" || lower == "silent") return false
            if (lower.contains("copied to clipboard") || lower.contains("tap to view clipboard") ||
                lower.contains("text copied") || lower.contains("ক্লিপবোর্ডে অনুলিপি") || lower.contains("ক্লিপবোর্ডে কপি")) return false
            return true
        }

        @Volatile
        var isCameraInUse: Boolean = false

        fun isCameraActive(): Boolean {
            if (isCameraInUse) return true
            val inst = instance ?: return false
            try {
                val activePkg = inst.rootInActiveWindow?.packageName?.toString()?.lowercase()
                if (activePkg != null && (activePkg.contains("camera") || activePkg.contains("snapcam"))) {
                    return true
                }
                val winList = try { inst.windows } catch (_: Exception) { null }
                if (winList != null) {
                    for (w in winList) {
                        val pkg = try { w.root?.packageName?.toString()?.lowercase() } catch (_: Exception) { null }
                        if (pkg != null && (pkg.contains("camera") || pkg.contains("snapcam"))) {
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
            return false
        }

        fun isSystemUiClipboardOverlayResId(resId: String?): Boolean {
            if (resId.isNullOrBlank()) return false
            val lower = resId.lowercase()
            // 1. Immediately ignore any view with resource ID containing privacy/camera/mic indicators
            if (lower.contains("privacy") || lower.contains("camera") || lower.contains("mic") ||
                lower.contains("sensor") || lower.contains("indicator")) {
                return false
            }
            // 2. Remove dangerous generic "chip" and "preview" substring checks.
            // Only match explicit clipboard overlay tokens:
            return lower.contains("clipboard_overlay") ||
                    lower.contains("clipboard_preview") ||
                    lower.contains("clipboard_chip") ||
                    (lower.contains("clipboard") && !lower.contains("privacy"))
        }

        fun inspectSystemUiClipboardNode(resId: String?, text: String? = null): Pair<Boolean, String?> {
            if (resId.isNullOrBlank() && text.isNullOrBlank()) {
                return Pair(false, null)
            }
            val lowerResId = resId?.lowercase() ?: ""
            if (lowerResId.contains("privacy") || lowerResId.contains("camera") || lowerResId.contains("mic") ||
                lowerResId.contains("sensor") || lowerResId.contains("indicator")) {
                return Pair(false, null)
            }

            var isOverlay = isSystemUiClipboardOverlayResId(lowerResId)
            var extractedText: String? = null

            if (!text.isNullOrBlank()) {
                val lowerText = text.trim().lowercase()
                if (lowerText.contains("copied to clipboard") || lowerText.contains("tap to view clipboard") ||
                    lowerText.contains("text copied") || lowerText.contains("ক্লিপবোর্ডে অনুলিপি") || lowerText.contains("ক্লিপবোর্ডে কপি")) {
                    isOverlay = true
                } else if (isLikelySystemUiClipboardText(text)) {
                    extractedText = text.trim()
                }
            }

            return Pair(isOverlay, extractedText)
        }

        fun inspectSystemUiClipboardNode(node: AccessibilityNodeInfo?, event: AccessibilityEvent? = null): Pair<Boolean, String?> {
            val rootResId = try { node?.viewIdResourceName } catch (e: Exception) { null }?.lowercase() ?: ""
            if (rootResId.contains("privacy") || rootResId.contains("camera") || rootResId.contains("mic") ||
                rootResId.contains("sensor") || rootResId.contains("indicator")) {
                return Pair(false, null)
            }

            var isOverlay = false
            var extractedText: String? = null

            val eventJoined = event?.text?.joinToString(" ")?.trim()?.lowercase() ?: ""
            if (eventJoined.contains("clipboard") || eventJoined.contains("copied") ||
                eventJoined.contains("অনুলিপি") || eventJoined.contains("কপি")) {
                if (!eventJoined.contains("privacy") && !eventJoined.contains("camera") && !eventJoined.contains("mic") &&
                    !eventJoined.contains("sensor") && !eventJoined.contains("indicator")) {
                    isOverlay = true
                }
            }

            fun traverse(curr: AccessibilityNodeInfo?, depth: Int) {
                if (curr == null || depth > 8) return
                try {
                    val resId = try { curr.viewIdResourceName } catch (e: Exception) { null }?.lowercase() ?: ""
                    if (resId.contains("privacy") || resId.contains("camera") || resId.contains("mic") ||
                        resId.contains("sensor") || resId.contains("indicator")) {
                        return
                    }

                    if (isSystemUiClipboardOverlayResId(resId)) {
                        isOverlay = true
                    }

                    val text = curr.text?.toString()?.trim()
                    if (!text.isNullOrEmpty()) {
                        val lower = text.lowercase()
                        if (lower.contains("copied to clipboard") || lower.contains("tap to view clipboard") ||
                            lower.contains("text copied") || lower.contains("ক্লিপবোর্ডে অনুলিপি") || lower.contains("ক্লিপবোর্ডে কপি")) {
                            isOverlay = true
                        } else if (isLikelySystemUiClipboardText(text)) {
                            if (resId.contains("clipboard_preview") || resId.contains("clipboard_chip") || resId.contains("clipboard") || resId.contains("text") || extractedText == null) {
                                extractedText = text
                            }
                        }
                    }

                    for (i in 0 until minOf(curr.childCount, 12)) {
                        val child = try { curr.getChild(i) } catch (e: Exception) { null } ?: continue
                        traverse(child, depth + 1)
                    }
                } catch (e: Exception) { /* ignore */ }
            }

            traverse(node, 0)
            return Pair(isOverlay, extractedText)
        }
    }

    private var clipboardManager: ClipboardManager? = null
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraCallback: android.hardware.camera2.CameraManager.AvailabilityCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastHandledText: String = ""
    private var lastHandledTime: Long = 0

    // Captured user selections
    var lastSelectedText: String? = null
        private set
    var lastSelectedTime: Long = 0
        private set

    // Captured long-clicked messages (e.g. WhatsApp, Telegram, Messenger)
    var lastLongClickedText: String? = null
        private set
    var lastLongClickedTime: Long = 0
        private set

    private var lastCaptureLaunchTime: Long = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add primary clip changed listener: ${e.message}")
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        try {
            val cb = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                private val activeCameras = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                override fun onCameraUnavailable(cameraId: String) {
                    activeCameras.add(cameraId)
                    isCameraInUse = true
                    Log.d(TAG, "Camera $cameraId unavailable/opened. In-use cameras: ${activeCameras.size}")
                }
                override fun onCameraAvailable(cameraId: String) {
                    activeCameras.remove(cameraId)
                    isCameraInUse = activeCameras.isNotEmpty()
                    Log.d(TAG, "Camera $cameraId available/closed. In-use cameras: ${activeCameras.size}")
                }
            }
            cameraCallback = cb
            cameraManager?.registerAvailabilityCallback(cb, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register camera availability callback: ${e.message}")
        }

        // Ensure background SyncService is actively running
        SyncService.ensureStarted(this)

        Log.i(TAG, "ClipboardAccessibilityService connected and listening.")
    }

    fun handleClipboardChange() {
        try {
            val clip = clipboardManager?.primaryClip
            var text: String? = null

            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                text = item.text?.toString() ?: item.coerceToText(this)?.toString()
            }

            if (!text.isNullOrEmpty()) {
                dispatchLocalClipboard(text)
            } else {
                // On Android 10+, clipboardManager.primaryClip returns null in background.
                // However, onPrimaryClipChangedListener only fires when content was indeed copied!
                val now = System.currentTimeMillis()
                if (now - lastHandledTime > 800) {
                    triggerClipboardCapture(delayMs = 40)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change: ${e.message}")
        }
    }

    fun updateLastHandled(text: String) {
        lastHandledText = text
        lastHandledTime = System.currentTimeMillis()
    }

    fun dispatchLocalClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        val now = System.currentTimeMillis()
        val hash = SyncService.sha256(text)

        // 1. Suppress echo if this text was received from PC within 15 seconds
        if (hash == SyncService.lastReceivedFromPcHash && (now - SyncService.lastReceivedFromPcTime < 15000)) {
            Log.d(TAG, "Suppressed clipboard dispatch: echo from PC.")
            return false
        }

        // 2. Debounce rapid identical events within 1.2s
        if (text == lastHandledText && now - lastHandledTime < 1200) {
            return false
        }

        lastHandledText = text
        lastHandledTime = now

        Log.i(TAG, "Dispatching local copy to laptop: ${text.take(30)}... (${text.length} chars)")
        val sent = SyncService.instance?.sendClipboardFromLocal(text) ?: false
        if (sent) {
            Toast.makeText(
                applicationContext,
                "📋 Sent to Laptop (${text.length} chars)",
                Toast.LENGTH_SHORT
            ).show()
        }
        return sent
    }

    fun setClipboardText(text: String) {
        mainHandler.post {
            try {
                val hash = SyncService.sha256(text)
                SyncService.lastReceivedFromPcHash = hash
                SyncService.lastReceivedFromPcTime = System.currentTimeMillis()
                lastHandledText = text
                lastHandledTime = System.currentTimeMillis()

                val clipData = ClipData.newPlainText("WiFiSync", text)
                clipboardManager?.setPrimaryClip(clipData)

                Toast.makeText(
                    applicationContext,
                    "📋 Copied from Laptop (${text.length} chars)",
                    Toast.LENGTH_SHORT
                ).show()

                Log.i(TAG, "Successfully updated device clipboard from PC.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update clipboard: ${e.message}")
            }
        }
    }

    fun extractSelectedText(event: AccessibilityEvent): String? {
        val node = try { event.source } catch (e: Exception) { null }
        val nodeText = try { node?.text?.toString() } catch (e: Exception) { null }

        // 1. Source node has direct text (standard TextView, EditText, etc.)
        if (!nodeText.isNullOrEmpty()) {
            var s = -1
            var e = -1

            if (node != null && node.textSelectionStart >= 0 && node.textSelectionEnd >= 0) {
                s = minOf(node.textSelectionStart, node.textSelectionEnd)
                e = maxOf(node.textSelectionStart, node.textSelectionEnd)
            }

            if (s == -1 || s == e) {
                if (event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex) {
                    s = minOf(event.fromIndex, event.toIndex)
                    e = maxOf(event.fromIndex, event.toIndex)
                }
            }

            if (s == -1 || s == e) {
                if (event.fromIndex >= 0 && event.itemCount > 0) {
                    s = event.fromIndex
                    e = minOf(nodeText.length, s + event.itemCount)
                }
            }

            if (s >= 0 && e in 0..nodeText.length && s < e) {
                val selected = nodeText.substring(s, e).trim()
                if (selected.isNotEmpty() && !isCopyKeywordOnly(selected)) {
                    return selected
                }
            }

            // If node has text but selection is 0-length (s == e, cursor tap in EditText),
            // return null so the entire field is NOT captured.
            if (s >= 0 && s == e) {
                return null
            }
        }

        // 2. Node is null or node.text is empty/null (Chrome, WebViews, Compose, custom canvas views)
        // In these engines, the browser/Compose engine places the selected string directly into event.text!
        if (event.text.isNotEmpty()) {
            val candidate = event.text.joinToString(" ").trim()
            if (candidate.isNotEmpty() && !isCopyKeywordOnly(candidate)) {
                return candidate
            }
        }

        return null
    }

    fun extractTextFromNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        val candidates = mutableListOf<Pair<String, Boolean>>() // text to isExplicitMessageBody

        fun collect(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 8) return
            try {
                val resId = try { n.viewIdResourceName } catch (e: Exception) { null }?.lowercase() ?: ""
                val text = n.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && !isCopyKeywordOnly(text)) {
                    val isMessageBody = resId.contains("message_text") ||
                            resId.contains("msg_text") ||
                            resId.contains("text_content") ||
                            resId.contains("conversation_text") ||
                            resId.contains("caption")
                    if (isMessageBody) {
                        candidates.add(Pair(text, true))
                    } else if (!text.matches(Regex("\\d{1,2}:\\d{2}(\\s*[ap]m)?"))) {
                        candidates.add(Pair(text, false))
                    }
                }
                val desc = n.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty() && !isCopyKeywordOnly(desc) && desc.length > 5 &&
                    !desc.matches(Regex("\\d{1,2}:\\d{2}(\\s*[ap]m)?"))) {
                    candidates.add(Pair(desc, false))
                }

                for (i in 0 until minOf(n.childCount, 12)) {
                    val child = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                    collect(child, d + 1)
                }
            } catch (e: Exception) { /* ignore */ }
        }

        collect(node, depth)
        // 1. Pick explicit message body text first
        val explicitBody = candidates.firstOrNull { it.second }?.first
        if (!explicitBody.isNullOrEmpty()) return explicitBody
        // 2. Otherwise pick the longest content candidate
        return candidates.map { it.first }.maxByOrNull { it.length }
    }

    fun findWhatsAppSelectedMessage(): String? {
        try {
            val root = rootInActiveWindow ?: return null
            fun search(n: AccessibilityNodeInfo?, d: Int): String? {
                if (n == null || d > 12) return null
                try {
                    val resId = try { n.viewIdResourceName } catch (e: Exception) { null }?.lowercase() ?: ""
                    val isMessage = resId.contains("message_text") ||
                            resId.contains("conversation_text") ||
                            resId.contains("caption")
                    if (isMessage) {
                        val isSelected = n.isSelected ||
                                (try { n.parent?.isSelected } catch (_: Exception) { false } == true)
                        if (isSelected) {
                            val text = n.text?.toString()?.trim()
                            if (!text.isNullOrEmpty() && !isCopyKeywordOnly(text)) return text
                        }
                    }
                    for (i in 0 until minOf(n.childCount, 16)) {
                        val child = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                        val res = search(child, d + 1)
                        if (!res.isNullOrEmpty()) return res
                    }
                } catch (e: Exception) { /* ignore */ }
                return null
            }
            return search(root, 0)
        } catch (e: Exception) {
            return null
        }
    }

    fun findSelectedTextInHierarchy(): String? {
        try {
            // First search rootInActiveWindow
            val activeRoot = rootInActiveWindow
            val activeText = findSelectedTextInNode(activeRoot)
            if (!activeText.isNullOrEmpty()) return activeText

            // Then search across all application windows if available
            val winList = try { windows } catch (e: Exception) { null }
            if (winList != null) {
                for (window in winList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = try { window.root } catch (e: Exception) { null } ?: continue
                        val text = findSelectedTextInNode(wRoot)
                        if (!text.isNullOrEmpty()) return text
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun findSelectedTextInNode(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        try {
            // 1. Check input focus
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val text = focused.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val s = minOf(focused.textSelectionStart, focused.textSelectionEnd)
                    val e = maxOf(focused.textSelectionStart, focused.textSelectionEnd)
                    if (s >= 0 && e in 0..text.length && s < e) {
                        val sub = text.substring(s, e).trim()
                        if (sub.isNotEmpty()) return sub
                    }
                }
            }

            // 2. Check accessibility focus
            val aFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (aFocused != null) {
                val text = aFocused.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val s = minOf(aFocused.textSelectionStart, aFocused.textSelectionEnd)
                    val e = maxOf(aFocused.textSelectionStart, aFocused.textSelectionEnd)
                    if (s >= 0 && e in 0..text.length && s < e) {
                        val sub = text.substring(s, e).trim()
                        if (sub.isNotEmpty()) return sub
                    }
                    if (aFocused.isSelected && !isCopyKeywordOnly(text)) {
                        return text.trim()
                    }
                }
            }

            // 3. Scan for any selected node in hierarchy
            val selectedText = findAnySelectedNodeText(root)
            if (!selectedText.isNullOrEmpty()) {
                return selectedText
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun findAnySelectedNodeText(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        try {
            if (node.isSelected) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && !isCopyKeywordOnly(t)) {
                    return t
                }
            }
            for (i in 0 until minOf(node.childCount, 12)) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                val res = findAnySelectedNodeText(child, depth + 1)
                if (!res.isNullOrEmpty()) return res
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun findTextNearCopyNode(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        try {
            // 1. Check parent's children (direct siblings of copy button)
            val parent = try { node.parent } catch (e: Exception) { null }
            if (parent != null) {
                for (i in 0 until minOf(parent.childCount, 16)) {
                    val child = try { parent.getChild(i) } catch (e: Exception) { null } ?: continue
                    if (child == node) continue
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrEmpty() && !isCopyKeywordOnly(text)) {
                        return text
                    }
                }

                // 2. Check grandparent container (e.g. Card with Code/Link and a Button layout)
                val grandParent = try { parent.parent } catch (e: Exception) { null }
                if (grandParent != null) {
                    for (i in 0 until minOf(grandParent.childCount, 16)) {
                        val child = try { grandParent.getChild(i) } catch (e: Exception) { null } ?: continue
                        if (child == parent) continue
                        val text = child.text?.toString()?.trim()
                        if (!text.isNullOrEmpty() && !isCopyKeywordOnly(text)) {
                            return text
                        }
                        for (j in 0 until minOf(child.childCount, 8)) {
                            val subChild = try { child.getChild(j) } catch (e: Exception) { null } ?: continue
                            val subText = subChild.text?.toString()?.trim()
                            if (!subText.isNullOrEmpty() && !isCopyKeywordOnly(subText)) {
                                return subText
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun isCopyAction(event: AccessibilityEvent): Boolean {
        // 0. Completely ignore input method / keyboard, launcher, and camera events
        if (isIgnoredPackage(event.packageName)) {
            return false
        }

        // 1. Standard AccessibilityNodeInfo copy/cut actions
        if (event.action == AccessibilityNodeInfo.ACTION_COPY || event.action == AccessibilityNodeInfo.ACTION_CUT) {
            return true
        }

        // 2. Check event content description and text
        val contentDesc = event.contentDescription?.toString()
        if (matchesKeyword(contentDesc)) return true

        val eventText = event.text.joinToString(" ")
        if (matchesKeyword(eventText)) return true

        val node = try { event.source } catch (e: Exception) { null }
        if (isIgnoredPackage(node?.packageName)) {
            return false
        }

        // 3. Check node's own text, description, and resource id
        val nodeText = try { node?.text?.toString() } catch (e: Exception) { null }
        if (matchesKeyword(nodeText)) return true

        val nodeDesc = try { node?.contentDescription?.toString() } catch (e: Exception) { null }
        if (matchesKeyword(nodeDesc)) return true

        val resId = try { node?.viewIdResourceName } catch (e: Exception) { null }
        if (isCopyViewId(resId)) return true

        // 4. Inspect node children (e.g. icon / text inside a button wrapper)
        if (node != null) {
            for (i in 0 until minOf(node.childCount, 16)) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                val cDesc = child.contentDescription?.toString()
                val cText = child.text?.toString()
                val cId = try { child.viewIdResourceName } catch (e: Exception) { null }
                if (matchesKeyword(cDesc) || matchesKeyword(cText) || isCopyViewId(cId)) {
                    return true
                }
            }

            // 6. Inspect direct parent and parent's other children (siblings of clicked node)
            val parent = try { node.parent } catch (e: Exception) { null }
            if (parent != null) {
                val pDesc = parent.contentDescription?.toString()
                val pText = parent.text?.toString()
                val pId = try { parent.viewIdResourceName } catch (e: Exception) { null }
                if (matchesKeyword(pDesc) || matchesKeyword(pText) || isCopyViewId(pId)) {
                    return true
                }
                for (j in 0 until minOf(parent.childCount, 8)) {
                    val sibling = try { parent.getChild(j) } catch (e: Exception) { null } ?: continue
                    if (sibling == node) continue
                    val sDesc = sibling.contentDescription?.toString()
                    val sText = sibling.text?.toString()
                    val sId = try { sibling.viewIdResourceName } catch (e: Exception) { null }
                    if (matchesKeyword(sDesc) || matchesKeyword(sText) || isCopyViewId(sId)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    fun handleCopyAction(event: AccessibilityEvent? = null) {
        if (event != null && isIgnoredPackage(event.packageName)) {
            return
        }

        val now = System.currentTimeMillis()
        var textToSend: String? = null

        // 1. Check if we have a fresh text selection recorded (within last 30 seconds)
        if (!lastSelectedText.isNullOrEmpty() && (now - lastSelectedTime < 30000)) {
            textToSend = lastSelectedText
            // Consume so subsequent different copies don't reuse stale selection
            lastSelectedText = null
            lastSelectedTime = 0
        }

        // 2. Check if we have a fresh long-clicked message (e.g. WhatsApp, Telegram within last 30s)
        if (textToSend.isNullOrEmpty() && !lastLongClickedText.isNullOrEmpty() && (now - lastLongClickedTime < 30000)) {
            textToSend = lastLongClickedText
            lastLongClickedText = null
            lastLongClickedTime = 0
        }

        // 3. Inspect WhatsApp active window for selected message if applicable
        if (textToSend.isNullOrEmpty() && (event?.packageName?.toString()?.contains("whatsapp") == true ||
                    event?.source?.packageName?.toString()?.contains("whatsapp") == true)) {
            textToSend = findWhatsAppSelectedMessage()
        }

        // 4. Inspect siblings of clicked node (e.g. in-app "Copy link", "Copy code" buttons)
        if (textToSend.isNullOrEmpty() && event != null) {
            val node = try { event.source } catch (e: Exception) { null }
            textToSend = findTextNearCopyNode(node)
        }

        // 5. Check active window focus hierarchy (focused input selection or selected nodes)
        if (textToSend.isNullOrEmpty()) {
            textToSend = findSelectedTextInHierarchy()
        }

        // 6. Check direct clipboardManager
        if (textToSend.isNullOrEmpty()) {
            try {
                val clip = clipboardManager?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    textToSend = item.text?.toString() ?: item.coerceToText(this)?.toString()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Dispatch immediately if text is identified
        if (!textToSend.isNullOrEmpty()) {
            dispatchLocalClipboard(textToSend)
            return
        }

        // Fallback for apps like Facebook/WhatsApp where the app writes to system clipboard
        // asynchronously after button tap, trigger foreground capture activity after settling delay.
        triggerClipboardCapture(delayMs = 120)
    }

    private fun handleSystemUiClipboardOverlay(event: AccessibilityEvent) {
        try {
            if (isCameraActive()) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return
            }

            val node = try { event.source } catch (e: Exception) { null }
            val (isOverlay, extractedText) = inspectSystemUiClipboardNode(node, event)

            if (isOverlay) {
                if (!extractedText.isNullOrEmpty() && !extractedText.endsWith("…") && !extractedText.endsWith("...")) {
                    Log.i(TAG, "Captured complete clipboard from SystemUI overlay: ${extractedText.take(30)}... (${extractedText.length} chars)")
                    dispatchLocalClipboard(extractedText)
                } else {
                    // SystemUI clipboard overlay chip confirmed, but text is truncated or in nested sub-node!
                    // Launch capture activity for full, exact clipboard content.
                    val now = System.currentTimeMillis()
                    if (now - lastHandledTime > 800) {
                        Log.i(TAG, "SystemUI clipboard overlay detected; triggering capture activity for full text.")
                        triggerClipboardCapture(delayMs = 40)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleSystemUiClipboardOverlay: ${e.message}")
        }
    }

    fun triggerClipboardCapture(delayMs: Long = 50) {
        val topPackage = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (isIgnoredPackage(topPackage) || isCameraActive()) {
            Log.d(TAG, "Aborting triggerClipboardCapture: top package ($topPackage) is ignored or camera is active")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastCaptureLaunchTime < 350) return
        lastCaptureLaunchTime = now

        mainHandler.postDelayed({
            val currentTop = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (isIgnoredPackage(currentTop) || isCameraActive()) {
                Log.d(TAG, "Aborting scheduled triggerClipboardCapture: current package ($currentTop) is ignored or camera is active")
                return@postDelayed
            }
            try {
                val intent = Intent(this, ClipboardCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }

                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        val optionsClass = android.app.ActivityOptions::class.java
                        val options = android.app.ActivityOptions.makeBasic()
                        try {
                            val methodCreator = optionsClass.getMethod(
                                "setPendingIntentCreatorBackgroundActivityStartMode",
                                Int::class.javaPrimitiveType
                            )
                            methodCreator.invoke(options, 1) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        } catch (_: Throwable) {}

                        try {
                            val methodBg = optionsClass.getMethod(
                                "setPendingIntentBackgroundActivityStartMode",
                                Int::class.javaPrimitiveType
                            )
                            methodBg.invoke(options, 1)
                        } catch (_: Throwable) {}

                        val pi = android.app.PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                            options.toBundle()
                        )
                        pi.send(this, 0, null, null, null, null, options.toBundle())
                        return@postDelayed
                    } catch (e: Throwable) {
                        Log.w(TAG, "PendingIntent launch fallback: ${e.message}")
                    }
                }

                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ClipboardCaptureActivity: ${e.message}")
            }
        }, delayMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: (try { event.source?.packageName?.toString() } catch (_: Exception) { null }) ?: ""

            // Completely ignore keyboards, launchers, and camera apps to prevent interference
            if (isIgnoredPackage(pkg) || (pkg == "com.android.systemui" && isCameraActive())) {
                return
            }

            // Direct check for explicit ACTION_COPY / ACTION_CUT on event
            if (event.action == AccessibilityNodeInfo.ACTION_COPY || event.action == AccessibilityNodeInfo.ACTION_CUT) {
                Log.i(TAG, "Explicit ACTION_COPY/CUT on event: ${event.eventType}")
                handleCopyAction(event)
                return
            }

            // Android 13+ SystemUI clipboard overlay inspection
            if (pkg == "com.android.systemui") {
                handleSystemUiClipboardOverlay(event)
                return
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val selected = extractSelectedText(event)
                    if (!selected.isNullOrEmpty()) {
                        lastSelectedText = selected
                        lastSelectedTime = System.currentTimeMillis()
                        Log.d(TAG, "Recorded text selection: ${selected.take(30)}... (${selected.length} chars)")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    // In chat apps (WhatsApp, Telegram, etc.), long click selects a message
                    val node = try { event.source } catch (e: Exception) { null }
                    val text = extractTextFromNodeHierarchy(node) ?: if (event.text.isNotEmpty()) {
                        val joined = event.text.joinToString(" ").trim()
                        if (!isCopyKeywordOnly(joined)) joined else null
                    } else null

                    if (!text.isNullOrEmpty()) {
                        lastLongClickedText = text
                        lastLongClickedTime = System.currentTimeMillis()
                        Log.d(TAG, "Recorded long-clicked message: ${lastLongClickedText?.take(30)}...")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    if (isCopyAction(event)) {
                        Log.i(TAG, "Copy action detected on UI event (type=${event.eventType}).")
                        handleCopyAction(event)
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Do NOT treat window state changes as copy events.
                    // This keeps keyboard clipboards and dialogs undisturbed.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            cameraCallback?.let { cameraManager?.unregisterAvailabilityCallback(it) }
        } catch (e: Exception) {
            // Ignore
        }
        cameraCallback = null
        cameraManager = null
        isCameraInUse = false
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        Log.i(TAG, "ClipboardAccessibilityService destroyed.")
    }
}
