package com.clipboardsync

import com.clipboardsync.update.UpdateManager
import org.junit.Assert.*
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testSemverComparison() {
        // Equal versions
        assertEquals(0, UpdateManager.compareSemver("1.0.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareSemver("v1.0.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareSemver("1.0", "1.0.0"))

        // Greater versions
        assertTrue(UpdateManager.compareSemver("1.0.1", "1.0.0") > 0)
        assertTrue(UpdateManager.compareSemver("v1.1.0", "1.0.9") > 0)
        assertTrue(UpdateManager.compareSemver("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateManager.compareSemver("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateManager.compareSemver("1.2.0-beta", "1.1.9") > 0)

        // Lesser versions
        assertTrue(UpdateManager.compareSemver("1.0.0", "1.0.1") < 0)
        assertTrue(UpdateManager.compareSemver("0.9.9", "1.0.0") < 0)
        assertTrue(UpdateManager.compareSemver("1.0.5", "1.1.0") < 0)
    }

    @Test
    fun testIsNewerVersion() {
        // Version code strictly takes precedence when specified (> 0)
        assertTrue(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.0",
            remoteCode = 2,
            currentVersion = "1.0.0",
            currentCode = 1
        ))

        assertFalse(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.1",
            remoteCode = 1,
            currentVersion = "1.0.1",
            currentCode = 2
        ))

        // Version codes equal -> fallback to semver
        assertFalse(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.0",
            remoteCode = 2,
            currentVersion = "1.0.0",
            currentCode = 2
        ))

        assertTrue(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.1",
            remoteCode = 2,
            currentVersion = "1.0.0",
            currentCode = 2
        ))

        // Semver fallback when version codes are 0 or equal
        assertTrue(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.1",
            remoteCode = 0,
            currentVersion = "1.0.0",
            currentCode = 0
        ))

        assertFalse(UpdateManager.isNewerVersion(
            remoteVersion = "1.0.0",
            remoteCode = 0,
            currentVersion = "1.0.0",
            currentCode = 0
        ))

        assertFalse(UpdateManager.isNewerVersion(
            remoteVersion = "0.9.5",
            remoteCode = 0,
            currentVersion = "1.0.0",
            currentCode = 0
        ))
    }

    @Test
    fun testParseUpdateInfoCustomSchema() {
        val json = """
            {
                "tag_name": "v1.0.1",
                "version": "1.0.1",
                "versionCode": 2,
                "name": "Wi-Fi Clipboard Sync v1.0.1",
                "apkUrl": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.1/app-debug.apk",
                "exeUrl": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.1/WiFiClipboardSync.exe",
                "changelog": "Added auto-update system and bug fixes",
                "publishedAt": "2026-10-09T10:00:00Z"
            }
        """.trimIndent()

        val info = UpdateManager.parseUpdateInfo(json)
        assertNotNull(info)
        assertEquals("1.0.1", info!!.versionName)
        assertEquals(2, info.versionCode)
        assertEquals("https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.1/app-debug.apk", info.apkUrl)
        assertEquals("Added auto-update system and bug fixes", info.changelog)
        assertEquals("2026-10-09T10:00:00Z", info.releaseDate)
    }

    @Test
    fun testParseUpdateInfoGitHubReleasesApiSchema() {
        val json = """
            {
                "tag_name": "v1.0.2",
                "name": "Release v1.0.2",
                "body": "Fixed background clipboard on Android 14",
                "published_at": "2026-10-10T12:00:00Z",
                "assets": [
                    {
                        "name": "WiFiClipboardSync.exe",
                        "browser_download_url": "https://github.com/.../WiFiClipboardSync.exe"
                    },
                    {
                        "name": "app-debug.apk",
                        "browser_download_url": "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.2/app-debug.apk"
                    }
                ]
            }
        """.trimIndent()

        val info = UpdateManager.parseUpdateInfo(json)
        assertNotNull(info)
        assertEquals("1.0.2", info!!.versionName)
        assertEquals("https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/download/v1.0.2/app-debug.apk", info.apkUrl)
        assertEquals("Fixed background clipboard on Android 14", info.changelog)
    }

    @Test
    fun testParseUpdateInfoInvalidJson() {
        assertNull(UpdateManager.parseUpdateInfo(""))
        assertNull(UpdateManager.parseUpdateInfo("{ invalid json }"))
        assertNull(UpdateManager.parseUpdateInfo("{}"))
    }
}
