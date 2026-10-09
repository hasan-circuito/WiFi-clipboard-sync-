package com.clipboardsync.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val changelog: String? = null,
    val releaseDate: String? = null
)

sealed class UpdateResult {
    data class UpdateAvailable(val info: UpdateInfo, val currentVersion: String) : UpdateResult()
    data class UpToDate(val currentVersion: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

object UpdateManager {
    private const val TAG = "UpdateManager"

    // Endpoints for checking updates
    const val RELEASE_ASSET_URL =
        "https://github.com/hasan-circuito/WiFi-clipboard-sync-/releases/latest/download/version.json"
    const val RAW_VERSION_URL =
        "https://raw.githubusercontent.com/hasan-circuito/WiFi-clipboard-sync-/main/version.json"
    const val GITHUB_API_URL =
        "https://api.github.com/repos/hasan-circuito/WiFi-clipboard-sync-/releases/latest"

    private fun logD(msg: String) {
        try { Log.d(TAG, msg) } catch (_: Exception) {}
    }

    private fun logW(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Exception) {}
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        try { Log.e(TAG, msg, tr) } catch (_: Exception) {}
    }

    /**
     * Checks whether an update is available against GitHub Releases.
     */
    suspend fun checkForUpdate(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName(context)
        val currentCode = getCurrentVersionCode(context)

        val urls = listOf(RELEASE_ASSET_URL, RAW_VERSION_URL, GITHUB_API_URL)
        var lastError: String? = null

        for (urlStr in urls) {
            try {
                logD("Checking update at $urlStr")
                val jsonStr = fetchString(urlStr) ?: continue
                val info = parseUpdateInfo(jsonStr) ?: continue

                val isNewer = isNewerVersion(
                    remoteVersion = info.versionName,
                    remoteCode = info.versionCode,
                    currentVersion = currentVersion,
                    currentCode = currentCode
                )

                return@withContext if (isNewer) {
                    UpdateResult.UpdateAvailable(info, currentVersion)
                } else {
                    UpdateResult.UpToDate(currentVersion)
                }
            } catch (e: Exception) {
                logW("Failed checking update at $urlStr: ${e.message}")
                lastError = e.message
            }
        }

        UpdateResult.Error(lastError ?: "Failed to check for updates")
    }

    /**
     * Parse either version.json format or GitHub API release format.
     */
    fun parseUpdateInfo(jsonStr: String): UpdateInfo? {
        if (jsonStr.isBlank()) return null
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject

            // Case 1: Custom version.json schema
            if (root.has("apkUrl")) {
                val rawVersion = root.get("version")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val tagName = root.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val version = when {
                    rawVersion.isNotEmpty() -> rawVersion
                    tagName.isNotEmpty() -> tagName.removePrefix("v").removePrefix("V")
                    else -> "1.0"
                }
                val code = root.get("versionCode")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val apkUrl = root.get("apkUrl")?.takeIf { !it.isJsonNull }?.asString ?: return null
                val changelog = root.get("changelog")?.takeIf { !it.isJsonNull }?.asString
                val publishedAt = root.get("publishedAt")?.takeIf { !it.isJsonNull }?.asString
                return UpdateInfo(
                    versionName = version,
                    versionCode = code,
                    apkUrl = apkUrl,
                    changelog = changelog,
                    releaseDate = publishedAt
                )
            }

            // Case 2: GitHub Releases API schema
            if (root.has("assets") && root.has("tag_name")) {
                val tagName = root.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val version = tagName.removePrefix("v").removePrefix("V")
                val body = root.get("body")?.takeIf { !it.isJsonNull }?.asString
                val assets = root.getAsJsonArray("assets")
                var apkUrl: String? = null

                if (assets != null) {
                    for (element in assets) {
                        val asset = element.asJsonObject
                        val name = asset.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString
                            break
                        }
                    }
                }

                if (apkUrl != null) {
                    return UpdateInfo(
                        versionName = version,
                        versionCode = 0,
                        apkUrl = apkUrl,
                        changelog = body,
                        releaseDate = root.get("published_at")?.takeIf { !it.isJsonNull }?.asString
                    )
                }
            }

            null
        } catch (e: Exception) {
            logE("Error parsing update info: ${e.message}", e)
            null
        }
    }

    /**
     * Compare semantic versioning strings (e.g., "1.0.1" vs "1.0.0").
     */
    fun compareSemver(v1: String, v2: String): Int {
        val clean1 = v1.trim().removePrefix("v").removePrefix("V").split("-")[0].split("+")[0]
        val clean2 = v2.trim().removePrefix("v").removePrefix("V").split("-")[0].split("+")[0]

        val parts1 = clean1.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    /**
     * Determines whether the remote version is strictly newer than current.
     */
    fun isNewerVersion(
        remoteVersion: String,
        remoteCode: Int,
        currentVersion: String,
        currentCode: Int
    ): Boolean {
        if (remoteCode > 0 && currentCode > 0 && remoteCode != currentCode) {
            return remoteCode > currentCode
        }
        return compareSemver(remoteVersion, currentVersion) > 0
    }

    /**
     * Downloads the APK file to app cache with real-time progress callbacks.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: ((progress: Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val updateDir = File(baseDir, "updates")
        if (!updateDir.exists()) {
            updateDir.mkdirs()
        }
        val destinationFile = File(updateDir, "update.apk")
        val tempFile = File(updateDir, "update.apk.tmp")
        if (tempFile.exists()) tempFile.delete()

        var currentUrl = downloadUrl
        var redirectCount = 0
        var connection: HttpURLConnection

        while (true) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "WiFiClipboardSync-Android")
            val code = connection.responseCode
            if (code in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                if (redirectUrl != null && redirectCount < 5) {
                    currentUrl = redirectUrl
                    redirectCount++
                    connection.disconnect()
                    continue
                }
            }
            break
        }

        if (connection.responseCode !in 200..299) {
            throw IOException("Failed to download APK: HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLength
        var bytesDownloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((bytesDownloaded * 100) / totalBytes).toInt()
                        withContext(Dispatchers.Main) {
                            onProgress?.invoke(progress)
                        }
                    }
                }
            }
        }

        if (destinationFile.exists()) destinationFile.delete()
        if (!tempFile.renameTo(destinationFile)) {
            tempFile.copyTo(destinationFile, overwrite = true)
            tempFile.delete()
        }

        destinationFile
    }

    /**
     * Prompts the user to install the downloaded APK using FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(
                    context,
                    "Please allow installing apps from this source to complete update",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            logE("Failed to launch package installer: ${e.message}", e)
            Toast.makeText(context, "Could not launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    private fun fetchString(urlStr: String): String? {
        var currentUrl = urlStr
        var redirectCount = 0
        var connection: HttpURLConnection

        while (true) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "WiFiClipboardSync-Android")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.instanceFollowRedirects = true

            val code = connection.responseCode
            if (code in 300..399) {
                val redirectUrl = connection.getHeaderField("Location")
                if (redirectUrl != null && redirectCount < 5) {
                    currentUrl = redirectUrl
                    redirectCount++
                    connection.disconnect()
                    continue
                }
            }
            break
        }

        return try {
            if (connection.responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}
