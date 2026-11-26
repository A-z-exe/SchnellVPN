package com.schnellvpn.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
    val size: Long = 0,
    /** Release asset with the combined `SHA256SUMS-Android.txt`, used when an asset has no digest. */
    val sumsUrl: String? = null,
    val apkName: String = ""
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
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    /** Combined checksum list published next to the APKs (`sha256sum` output). */
    private const val SUMS_NAME = "SHA256SUMS-Android.txt"
    private const val MAX_SUMS_BYTES = 64 * 1024

    /**
     * Picks the release asset for this device. New releases ship one APK per
     * ABI (`…-arm64-v8a.apk`); older releases have a single APK without an
     * ABI suffix and are used as a fallback.
     */
    fun pickApk(names: List<String>, abis: List<String>): String? {
        for (abi in abis) {
            names.firstOrNull { it.endsWith("-$abi.apk", ignoreCase = true) }
                ?.let { return it }
        }
        return names.firstOrNull { name ->
            KNOWN_ABIS.none { name.endsWith("-$it.apk", ignoreCase = true) }
        }
    }

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

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
            val apkAssets = ArrayList<JSONObject>()
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    apkAssets.add(a)
                }
            }
            val chosenName = pickApk(
                apkAssets.map { it.optString("name", "") },
                Build.SUPPORTED_ABIS.toList()
            ) ?: return@withContext null
            val chosen = apkAssets.firstOrNull { it.optString("name", "") == chosenName }
                ?: return@withContext null
            val apkUrl = chosen.optString("browser_download_url", "")
            val sha = chosen.optString("digest", "")
                .removePrefix("sha256:")
                .takeIf { it.isNotBlank() }
            val size = chosen.optLong("size", 0)
            if (apkUrl.isNullOrBlank()) return@withContext null
            var sumsUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "") == SUMS_NAME) {
                    sumsUrl = a.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                    break
                }
            }

            UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apkUrl,
                sha256 = sha,
                notes = obj.optString("body", ""),
                size = size,
                sumsUrl = sumsUrl,
                apkName = chosenName
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the APK to a `.part` file and resumes it with an HTTP Range
     * request after a dropped connection, so a phone call or a flapping network
     * does not restart the whole download. The final file only appears after
     * the SHA-256 from the release (asset digest, or the published
     * `SHA256SUMS-Android.txt`) matches; without a hash nothing is installed.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, APK_NAME)
        val part = File(dir, "$APK_NAME.part")
        var expected = info.sha256?.takeIf { it.isNotBlank() }
        var attempt = 0
        while (attempt < 5) {
            if (attempt > 0) delay(2_000)
            attempt++
            try {
                if (info.size > 0 && part.isFile && part.length() > info.size) {
                    part.delete()
                } else if (info.size > 0 && part.isFile && part.length() == info.size) {
                    onProgress(100)
                } else {
                    fetchInto(part, info, onProgress)
                }
                if (expected == null) expected = expectedFromSums(info)
                if (expected == null) {
                    // No trustworthy hash yet: keep retrying instead of installing blindly.
                    continue
                }
                if (!sha256(part).equals(expected, ignoreCase = true)) {
                    part.delete()
                    continue
                }
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                return@withContext out
            } catch (e: Exception) {
                // Keep the .part file: the next attempt asks for the rest of the bytes.
            }
        }
        null
    }

    private fun fetchInto(part: File, info: UpdateInfo, onProgress: (Int) -> Unit) {
        val offset = if (part.isFile) part.length() else 0L
        val conn = openFollowing(info.apkUrl, offset)
        try {
            val code = conn.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                part.delete()
                throw IOException("HTTP $code")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0
            val start = if (append) offset else 0L
            if (!append) part.delete()
            val total = when {
                info.size > 0 -> info.size
                append -> totalFromContentRange(conn.getHeaderField("Content-Range"))
                conn.contentLengthLong > 0 -> start + conn.contentLengthLong
                else -> 0L
            }
            if (total > 0) onProgress(((start * 100) / total).toInt().coerceIn(0, 100))
            FileOutputStream(part, append).use { output ->
                var done = start
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
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
                if (total > 0 && done < total) throw IOException("incomplete download")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Follows redirects manually so the Range header survives GitHub's asset redirect. */
    private fun openFollowing(urlStr: String, offset: Long): HttpURLConnection {
        var current = URL(urlStr)
        repeat(6) {
            if (current.protocol != "http" && current.protocol != "https") {
                throw IOException("Only http/https links are supported")
            }
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "SchnellVPN")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect without Location")
                current = URL(current, location)
                conn.disconnect()
                return@repeat
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    private fun totalFromContentRange(header: String?): Long =
        header?.substringAfter('/', "")?.toLongOrNull() ?: 0L

    /**
     * Reads the expected hash for this APK from the release's
     * `SHA256SUMS-Android.txt` (lines look like `<hash>  <file>`).
     */
    private fun expectedFromSums(info: UpdateInfo): String? {
        val url = info.sumsUrl?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val conn = openFollowing(url, 0)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(4096)
                    while (sb.length < MAX_SUMS_BYTES) {
                        val read = reader.read(buf)
                        if (read <= 0) break
                        sb.append(buf, 0, read)
                    }
                    sb.toString()
                }
                val target = info.apkName.substringAfterLast('/')
                if (target.isBlank()) return null
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { line ->
                        val parts = line.split(Regex("\\s+"), limit = 2)
                        parts.size == 2 && parts[1].trimStart('*').substringAfterLast('/') == target
                    }
                    ?.split(Regex("\\s+"), limit = 2)
                    ?.firstOrNull()
                    ?.takeIf { it.length == 64 }
            } finally {
                conn.disconnect()
            }
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
