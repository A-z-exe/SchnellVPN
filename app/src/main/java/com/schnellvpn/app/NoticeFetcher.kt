package com.schnellvpn.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppNotice(
    val id: String,
    val level: String,
    val title: String,
    val message: String,
    val update: Boolean
)

/**
 * اعلان‌های استاتیک روی GitHub Pages (notices.json).
 * اگر صفحه در دسترس نباشد یا خراب باشد، هیچ اعلانی نمایش داده نمی‌شود.
 */
object NoticeFetcher {

    private const val URL_JSON = "https://a-z-exe.github.io/SchnellVPN/notices.json"
    private const val PREFS = "schnellvpn_notices"
    private const val KEY_SEEN = "seen"
    private const val MAX_BYTES = 256 * 1024

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seen(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()

    fun markSeen(context: Context, id: String) {
        prefs(context).edit().putStringSet(KEY_SEEN, seen(context) + id).apply()
    }

    suspend fun fetchUnseen(context: Context): List<AppNotice> = withContext(Dispatchers.IO) {
        val known = seen(context)
        runCatching { fetch(context) }.getOrDefault(emptyList())
            .filter { it.id !in known }
    }

    private fun fetch(context: Context): List<AppNotice> {
        val conn = URL(URL_JSON).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty(
            "User-Agent",
            "SchnellVPN/${UpdateManager.currentVersion(context)}"
        )
        conn.setRequestProperty("Accept", "application/json")
        val body = try {
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    if (out.size() > MAX_BYTES) return emptyList()
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }

        val array = JSONObject(body).optJSONArray("notices") ?: return emptyList()
        val version = UpdateManager.currentVersion(context)
        val lang = ProfileManager.loadLanguage(context)
        val result = ArrayList<AppNotice>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            if (!platformMatches(item.optJSONArray("platforms"))) continue
            val maxVersion = item.optString("max_version").trim()
            if (maxVersion.isNotEmpty() && UpdateManager.isNewer(version, maxVersion)) continue
            val minVersion = item.optString("min_version").trim()
            if (minVersion.isNotEmpty() && UpdateManager.isNewer(minVersion, version)) continue
            val title = localized(item.opt("title"), lang) ?: continue
            val message = localized(item.opt("message"), lang) ?: continue
            val level = item.optString("level", "info").lowercase()
            result.add(
                AppNotice(
                    id = id,
                    level = if (level in setOf("info", "warning", "critical")) level else "info",
                    title = title,
                    message = message,
                    update = item.optBoolean("update", false)
                )
            )
        }
        return result
    }

    private fun platformMatches(platforms: JSONArray?): Boolean {
        if (platforms == null) return true
        for (index in 0 until platforms.length()) {
            if (platforms.optString(index) == "android") return true
        }
        return false
    }

    private fun localized(value: Any?, lang: String): String? {
        if (value is String) return value.trim().ifEmpty { null }
        if (value !is JSONObject) return null
        for (key in listOf(lang, "en", "fa")) {
            val text = value.optString(key)
            if (text.isNotBlank()) return text
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val text = value.optString(keys.next())
            if (text.isNotBlank()) return text
        }
        return null
    }
}