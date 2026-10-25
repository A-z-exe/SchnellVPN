package com.schnellvpn.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String
)

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class Available(val info: UpdateInfo) : UpdateUiState()
    data class Downloading(val percent: Int) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

/**
 * به‌روزرسانی درون‌برنامه‌ای از GitHub Releases:
 * چک نسخه → دانلود APK → بررسی SHA-256 → اجرای نصب‌کننده‌ی اندروید.
 */
object UpdateManager {

    private const val API_URL = "https://api.github.com/repos/A-z-exe/SchnellVPN/releases/latest"
    private const val APK_NAME = "SchnellVPN-update.apk"

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    private fun versionParts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    fun isNewer(remote: String, current: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "SchnellVPN")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = JSONObject(body)
            val tag = obj.optString("tag_name", "")
            if (!isNewer(tag, currentVersion(context))) return@withContext null

            val assets = obj.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    sha = a.optString("digest", "")
                        .removePrefix("sha256:")
                        .takeIf { it.isNotBlank() }
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return@withContext null

            UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apkUrl,
                sha256 = sha,
                notes = obj.optString("body", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, APK_NAME)
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "SchnellVPN")
            try {
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (!info.sha256.isNullOrBlank()) {
                val actual = sha256(out)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    out.delete()
                    return@withContext null
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownSourcesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // اگر نصب‌کننده باز نشد، کاربر باید دستی اجازه بدهد
        }
    }
}
