package com.schnellvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads a subscription and converts it to a list of [VpnServer].
 * Supported bodies: plain link list, Base64 link list, JSON array / object (v2rayN style).
 * Only servers that can actually be turned into an Xray outbound are returned.
 */
object SubscriptionFetcher {

    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val CACHE_PREFS = "schnellvpn_sub_cache"

    /**
     * Downloads a subscription with gzip + conditional caching (ETag / Last-Modified):
     * an unchanged subscription costs a 304 and reuses the cached body instead of
     * downloading it again — less mobile data and radio time.
     */
    fun fetchAndParse(context: Context, subUrl: String): List<VpnServer> {
        val url = subUrl.trim()
        val key = sha256Hex(url)
        val cacheDir = File(context.cacheDir, "subscriptions")
        val cacheFile = File(cacheDir, "$key.txt")
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val cachedBody = if (cacheFile.isFile) {
            runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
        } else null

        val etag = if (cachedBody != null) prefs.getString("etag_$key", null) else null
        val lastModified = if (cachedBody != null) prefs.getString("lm_$key", null) else null

        val downloaded = download(url, etag, lastModified)
        val body = when {
            downloaded != null -> {
                cacheDir.mkdirs()
                runCatching { cacheFile.writeText(downloaded.body, Charsets.UTF_8) }
                prefs.edit()
                    .putString("etag_$key", downloaded.etag)
                    .putString("lm_$key", downloaded.lastModified)
                    .apply()
                downloaded.body
            }
            cachedBody != null -> cachedBody // HTTP 304: nothing changed
            else -> return emptyList()
        }
        return parseContent(body)
    }

    /** Share-link protocols the bundled Xray core cannot dial; used for a clear UI hint. */
    fun unsupportedInText(rawInput: String): List<String> {
        val lower = rawInput.lowercase()
        val found = mutableListOf<String>()
        if ("hysteria2://" in lower || "hy2://" in lower) found.add("Hysteria2")
        if ("hysteria://" in lower) found.add("Hysteria")
        if ("tuic://" in lower) found.add("TUIC")
        if ("wireguard://" in lower || "warp://" in lower) found.add("WireGuard")
        return found
    }

    /** Pure function (no network) — the part covered by unit tests. */
    fun parseContent(rawInput: String): List<VpnServer> {
        val raw = rawInput.trim().removePrefix("\uFEFF").trim()
        if (raw.isEmpty()) return emptyList()

        if (raw.startsWith("[") || raw.startsWith("{")) return parseJson(raw)

        val text = if (containsKnownScheme(raw)) raw else (tryBase64Decode(raw) ?: raw)
        return parseLinks(text)
    }

    /** آیا ورودی یک لینک کانفیگ مستقیم است (vless:// vmess:// trojan:// ss://)؟ */
    fun isDirectLink(text: String): Boolean = startsWithKnownScheme(text.trim())

    // ---------------------------------------------------------------- JSON

    private fun parseJson(json: String): List<VpnServer> {
        val arr = try {
            if (json.startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        } catch (e: Exception) {
            return emptyList()
        }

        val servers = mutableListOf<VpnServer>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val remarks = obj.optString("remarks", "")

            val proxy: JSONObject = when {
                obj.has("protocol") -> obj // the element itself is an outbound
                else -> {
                    val outbounds = obj.optJSONArray("outbounds") ?: continue
                    var found: JSONObject? = null
                    for (j in 0 until outbounds.length()) {
                        val ob = outbounds.optJSONObject(j) ?: continue
                        if (ob.optString("tag") == "proxy") { found = ob; break }
                    }
                    found ?: outbounds.optJSONObject(0) ?: continue
                }
            }
            if (proxy.optString("protocol") in setOf("freedom", "blackhole", "dns", "")) continue

            val link = proxy.toString()
            val endpoint = XrayConfigBuilder.endpointOf(link) ?: continue
            val name = remarks.ifEmpty { endpoint.first }
            servers.add(
                VpnServer(servers.size + 1, "🌐", name, labelOf(proxy), link, null)
            )
        }
        return servers
    }

    // ---------------------------------------------------------------- links

    private fun parseLinks(text: String): List<VpnServer> {
        val seen = HashSet<String>()
        val servers = mutableListOf<VpnServer>()

        for (line in text.lines()) {
            val link = line.trim()
            if (link.isEmpty() || !startsWithKnownScheme(link)) continue

            val outbound = try {
                XrayConfigBuilder.parseOutbound(link)
            } catch (e: Exception) {
                continue // skip links we can't turn into a working config
            }

            val name = remarkOf(link).ifEmpty {
                XrayConfigBuilder.endpointOf(link)?.first ?: "Server ${servers.size + 1}"
            }
            // کانفیگ‌های لوکال (127.0.0.1) ممکن است لینک یکسان و نام متفاوت داشته باشند؛
            // کاربر انتظار دارد همه‌شان جدا نمایش داده شوند.
            val key = if (ServerListOps.isLocalLink(link)) "$link|$name" else link
            if (!seen.add(key)) continue
            servers.add(VpnServer(servers.size + 1, "🌐", name, labelOf(outbound), link, null))
        }
        return servers
    }

    private fun remarkOf(link: String): String {
        if (link.startsWith("vmess://", true)) {
            val fromJson = runCatching {
                XrayConfigBuilder.decodeVmessJson(link).optString("ps", "")
            }.getOrDefault("")
            if (fromJson.isNotEmpty()) return fromJson.trim()
        }
        return XrayConfigBuilder.decodeUrl(link.substringAfter('#', "")).trim()
    }

    private fun labelOf(outbound: JSONObject): String {
        val ss = outbound.optJSONObject("streamSettings")
        val network = ss?.optString("network", "tcp") ?: "tcp"
        val security = ss?.optString("security", "none") ?: "none"
        return when (outbound.optString("protocol")) {
            "vless" -> when {
                security == "reality" -> "VLESS · Reality"
                network == "ws" -> "VLESS · WS"
                network == "grpc" -> "VLESS · gRPC"
                network == "xhttp" -> "VLESS · XHTTP"
                else -> "VLESS"
            }
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "socks" -> "SOCKS"
            else -> outbound.optString("protocol").uppercase()
        }
    }

    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "socks://")

    private fun startsWithKnownScheme(s: String) = schemes.any { s.startsWith(it, ignoreCase = true) }

    private fun containsKnownScheme(text: String) =
        text.lines().any { startsWithKnownScheme(it.trim()) }

    private fun tryBase64Decode(text: String): String? {
        val decoded = Base64Compat.decodeToString(text) ?: return null
        return if (containsKnownScheme(decoded)) decoded else null
    }

    // ---------------------------------------------------------------- network

    private class Download(val body: String, val etag: String?, val lastModified: String?)

    /**
     * Returns null for HTTP 304 (cached copy is still valid); throws on failure.
     * Conditional headers are only sent to the original URL, not to redirect targets.
     */
    private fun download(urlStr: String, etag: String?, lastModified: String?): Download? {
        var current = URL(urlStr)
        repeat(MAX_REDIRECTS + 1) {
            if (current.protocol != "http" && current.protocol != "https") {
                throw IOException("Only http/https links are supported")
            }
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false // we follow manually (also http -> https)
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "SchnellVPN/1.0")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified)
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    current = URL(current, location)
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                return Download(
                    body = readLimited(conn),
                    etag = conn.getHeaderField("ETag"),
                    lastModified = conn.getHeaderField("Last-Modified")
                )
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readLimited(conn: HttpURLConnection): String {
        val gzipped = conn.getHeaderField("Content-Encoding")?.contains("gzip", true) == true
        val raw: InputStream = conn.inputStream
        val input: InputStream = if (gzipped) GZIPInputStream(raw) else raw
        input.use {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("Subscription is too large")
                out.write(chunk, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
