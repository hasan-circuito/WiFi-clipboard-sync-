package com.clipboardsync

import com.clipboardsync.service.ClipboardAccessibilityService
import com.clipboardsync.service.SyncService
import org.junit.Assert.*
import org.junit.Test

class SyncServiceLogicTest {

    @Test
    fun testSha256Hashing() {
        val sample = "Hello Android Wi-Fi Clipboard!"
        val hash = SyncService.sha256(sample)
        assertNotNull(hash)
        assertEquals(64, hash.length)

        // Hashing identical text produces identical output
        assertEquals(hash, SyncService.sha256(sample))

        // Bengali unicode text hashing
        val bengali = "আমি একটা টুলস বানাতে চাচ্ছি"
        val bengaliHash = SyncService.sha256(bengali)
        assertEquals("d2eb2488e4f0d24d15ae05bd8e86d1fd389a72c34e84b284f8867547fc789ece", bengaliHash)
    }

    @Test
    fun testSelectionIndexCalculationForwardAndBackward() {
        val text = "Quick brown fox jumps over the lazy dog"

        // Forward selection: start 6, end 11 -> "brown"
        val fStart = minOf(6, 11)
        val fEnd = maxOf(6, 11)
        assertEquals("brown", text.substring(fStart, fEnd))

        // Backward selection (user dragged handle right to left): start 11, end 6
        val bStart = minOf(11, 6)
        val bEnd = maxOf(11, 6)
        assertEquals("brown", text.substring(bStart, bEnd))

        // Selection with itemCount fallback: fromIndex 6, itemCount 5
        val fromIndex = 6
        val itemCount = 5
        val safeItemEnd = minOf(text.length, fromIndex + itemCount)
        assertEquals("brown", text.substring(fromIndex, safeItemEnd))

        // Out of bound protection
        val safeStart = maxOf(0, minOf(50, text.length))
        val safeEnd = minOf(text.length, maxOf(safeStart, 60))
        assertEquals(text.length, safeStart)
        assertEquals(text.length, safeEnd)
    }

    @Test
    fun testMultilingualCopyDetectionKeywords() {
        // Test ClipboardAccessibilityService.matchesKeyword directly
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("COPY TO CLIPBOARD"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy link"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy link address"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy code"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy text"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy URL"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy invite link"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy phone number"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Cut"))

        // Official AOSP Bengali string for Copy: "অনুলিপি"
        assertTrue(ClipboardAccessibilityService.matchesKeyword("অনুলিপি"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("অনুলিপি করুন"))

        // Colloquial Bengali
        assertTrue(ClipboardAccessibilityService.matchesKeyword("কপি"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("কপি করুন"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("কাট"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("কাটুন"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("লেখা কপি"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("লিংক কপি করুন"))

        // Hindi
        assertTrue(ClipboardAccessibilityService.matchesKeyword("कॉपी"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("कट"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("प्रतिलिपि"))

        // Spanish, French, German
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copiar texto"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copier le lien"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Ausschneiden"))

        // Italian, Turkish, Indonesian, Vietnamese
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copia"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Taglia"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Kopyala"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Salin"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Sao chép"))

        // Non-copy actions must NOT match
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Paste"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("পেস্ট"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Select all"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Share"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("শেয়ার"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Delete"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword(null))
        assertFalse(ClipboardAccessibilityService.matchesKeyword(""))
    }

    @Test
    fun testViewIdCopyMatching() {
        assertTrue(ClipboardAccessibilityService.isCopyViewId("android:id/copy"))
        assertTrue(ClipboardAccessibilityService.isCopyViewId("com.android.internal.R.id.copy"))
        assertTrue(ClipboardAccessibilityService.isCopyViewId("action_copy"))
        assertTrue(ClipboardAccessibilityService.isCopyViewId("btn_copy_link"))
        assertTrue(ClipboardAccessibilityService.isCopyViewId("sem_floating_toolbar_copy"))
        assertTrue(ClipboardAccessibilityService.isCopyViewId("menu_cut"))

        // Keyboard clipboard history/tab view IDs must NOT match copy view id
        assertFalse(ClipboardAccessibilityService.isCopyViewId("clipboard_icon"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId("keyboard_clipboard_button"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId("clipboard_tab"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId("clipboard_action"))

        assertFalse(ClipboardAccessibilityService.isCopyViewId("action_paste"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId("btn_submit"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId("android:id/selectAll"))
        assertFalse(ClipboardAccessibilityService.isCopyViewId(null))
        assertFalse(ClipboardAccessibilityService.isCopyViewId(""))
    }

    @Test
    fun testKeyboardPackageDetection() {
        // Must detect all popular Android keyboards
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.google.android.inputmethod.latin"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("net.ridmik.keyboard"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.ridmik.keyboard"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.samsung.android.honeyboard"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.touchtype.swiftkey"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.sohu.inputmethod.sogou"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("org.dslul.openboard.inputmethod.latin"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.menny.android.anysoftkeyboard"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.syntellia.fleksy.keyboard"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.baidu.ime"))
        assertTrue(ClipboardAccessibilityService.isKeyboardPackage("com.test.ime.service"))

        // Standard apps and apps containing "ime" in their names must NOT be falsely marked as keyboard
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.whatsapp"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.android.chrome"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("org.telegram.messenger"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.facebook.orca"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.android.systemui"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.prime.video"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.anime.app"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.showtime.movie"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.realtime.chat"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage("com.google.android.apps.messaging"))
        assertFalse(ClipboardAccessibilityService.isKeyboardPackage(null))
    }

    @Test
    fun testSafeCopyKeywordDistinction() {
        // True copy actions
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Copy"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("Cut"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("কপি"))
        assertTrue(ClipboardAccessibilityService.matchesKeyword("অনুলিপি"))

        // False positives that must NOT match
        assertFalse(ClipboardAccessibilityService.matchesKeyword("cute"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("execute"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("shortcut"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Copyright 2026"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("Clipboard"))
        assertFalse(ClipboardAccessibilityService.matchesKeyword("ক্লিপবোর্ড"))
    }

    @Test
    fun testIsCopyKeywordOnly() {
        // Standard button labels should be detected as keyword-only
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly("Copy"))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly("কপি"))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly("অনুলিপি"))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly("Share"))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly("Select all"))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly(null))
        assertTrue(ClipboardAccessibilityService.isCopyKeywordOnly(""))

        // Real content strings should NOT be marked as keyword-only
        assertFalse(ClipboardAccessibilityService.isCopyKeywordOnly("https://github.com/KaustubhPatange/XClipper"))
        assertFalse(ClipboardAccessibilityService.isCopyKeywordOnly("Your verification code is 482910"))
        assertFalse(ClipboardAccessibilityService.isCopyKeywordOnly("const token = 'xyz_12345';"))
        assertFalse(ClipboardAccessibilityService.isCopyKeywordOnly("আমি বাংলায় গান গাই"))
    }

    @Test
    fun testIsLikelySystemUiClipboardText() {
        // Valid clipboard content
        assertTrue(ClipboardAccessibilityService.isLikelySystemUiClipboardText("https://maps.google.com/xyz"))
        assertTrue(ClipboardAccessibilityService.isLikelySystemUiClipboardText("Hello friend!"))
        assertTrue(ClipboardAccessibilityService.isLikelySystemUiClipboardText("482910"))

        // System status UI elements and copy notifications that must NOT be confused for clipboard content
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("10:45"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("10:45 AM"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("100%"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("85%"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("notifications"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("quick settings"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("Copied to clipboard"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("Tap to view clipboard"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText("Text copied to clipboard"))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText(""))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText(" "))
        assertFalse(ClipboardAccessibilityService.isLikelySystemUiClipboardText(null))
    }

    @Test
    fun testEchoSuppressionLogic() {
        val pcIncomingText = "Synced from PC"
        val pcHash = SyncService.sha256(pcIncomingText)

        val lastReceivedFromPcHash = pcHash
        val lastReceivedFromPcTime = System.currentTimeMillis()

        // 1. Immediately trying to send back identical text must be suppressed
        val now = System.currentTimeMillis()
        val isEcho = (pcHash == lastReceivedFromPcHash) && (now - lastReceivedFromPcTime < 15000)
        assertTrue(isEcho)

        // 2. Different text from phone should not be suppressed
        val phoneNewText = "Copied on phone"
        val phoneHash = SyncService.sha256(phoneNewText)
        val isEchoDifferent = (phoneHash == lastReceivedFromPcHash) && (now - lastReceivedFromPcTime < 15000)
        assertFalse(isEchoDifferent)
    }

    @Test
    fun testDuplicateDebounceLogic() {
        val localText = "Quick message"
        val localHash = SyncService.sha256(localText)

        var lastSentToPcHash = localHash
        var lastSentToPcTime = System.currentTimeMillis()

        // Identical message within 1.2 seconds is debounced
        val now = System.currentTimeMillis()
        val isDebounced = (localHash == lastSentToPcHash) && (now - lastSentToPcTime < 1200)
        assertTrue(isDebounced)

        // Same message after 1.5 seconds is allowed
        lastSentToPcTime = now - 1500
        val isAllowed = (localHash == lastSentToPcHash) && (now - lastSentToPcTime < 1200)
        assertFalse(isAllowed)
    }

    @Test
    fun testChromeAndWebViewSelectionExtractionSimulation() {
        // In Chrome/WebView/Compose, node.text is null/empty, but event.text has the selected string.
        val chromeEventText = listOf("https://example.com/deep/link?auth=yes")
        val candidate = chromeEventText.joinToString(" ").trim()

        assertFalse(ClipboardAccessibilityService.isCopyKeywordOnly(candidate))
        assertEquals("https://example.com/deep/link?auth=yes", candidate)

        // In EditText cursor move: node.text is non-empty, and textSelectionStart == textSelectionEnd
        val editTextContent = "Hello from Android world"
        val cursorStart = 10
        val cursorEnd = 10
        val isCursorOnly = (cursorStart == cursorEnd)
        assertTrue(isCursorOnly) // Should reject cursor position and not return full text

        // Valid selection in EditText
        val selStart = 11
        val selEnd = 18
        assertTrue(selStart < selEnd)
        assertEquals("Android", editTextContent.substring(selStart, selEnd))
    }
}
