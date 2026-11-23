package com.schnellvpn.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class RemoteCommand(
    val id: Int,
    val kind: String,
    val payload: JSONObject
)

data class AdminHello(
    val notices: List<AppNotice>,
    val commands: List<RemoteCommand>
)

/**
 * اتصال بی‌نام به Worker ادمین (Cloudflare): هر نصب یک install_id تصادفی
 * می‌سازد و فقط پلتفرم/نسخه/زبان را می‌فرستد. هر خطایی یعنی «کاری نیست»
 * تا در دسترس نبودن Worker هرگز جلوی کار برنامه را نگیرد.
 */
object AdminClient {

    private const val BASE = "https://schnellvpn-admin.a-z-exe.workers.dev"
    private const val PREFS = "schnellvpn_admin"
    private const val KEY_INSTALL = "install_id"
    private const val MAX_BYTES = 512 * 1024
    private val KINDS = setOf("notice", "force_update", "disconnect", "setting")

    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_INSTALL, null)
        if (!saved.isNullOrBlank()) return saved
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL, id).apply()
        return id
    }

    suspend fun hello(context: Context, lang: String): AdminHello? = withContext(Dispatchers.IO) {
        val body = runCatching {
            post(
                "/v1/hello",
                JSONObject()
                    .put("install_id", installId(context))
                    .put("platform", "android")
                    .put("version", UpdateManager.currentVersion(context))
                    .put("locale", lang)
            )
        }.getOrNull() ?: return@withContext null

        runCatching {
            val data = JSONObject(body)
            val commands = ArrayList<RemoteCommand>()
            val array = data.optJSONArray("commands") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", -1)
                val kind = item.optString("kind")
                if (id < 0 || kind !in KINDS) continue
                commands.add(
                    RemoteCommand(id, kind, item.optJSONObject("payload") ?: JSONObject())
                )
            }
            AdminHello(NoticeFetcher.parse(context, body), commands)
        }.getOrNull()
    }

    suspend fun ack(context: Context, commandId: Int, status: String = "done") {
        withContext(Dispatchers.IO) {
            runCatching {
                post(
                    "/v1/ack",
                    JSONObject()
                        .put("install_id", installId(context))
                        .put("command_id", commandId)
                        .put("status", status)
                )
            }
        }
    }

    private fun post(path: String, payload: JSONObject): String? {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SchnellVPN-Admin")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    if (out.size() > MAX_BYTES) return null
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }
}
