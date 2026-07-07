package com.schnellvpn.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * دانلود و پارس لینک‌های Subscription. این نسخه پایدارتر است و:
 * - تایم‌اوت‌ها و ری‌دایرکت‌ها را بهتر هندل می‌کند
 * - تشخیص Base64 و محتوای خط‌به‌خط قوی‌تر است
 * - خطاها را لاگ می‌کند تا دلایل شکست واضح باشد
 */
object SubscriptionFetcher {
    private const val TAG = "SubscriptionFetcher"

    fun fetchAndParse(subUrl: String): List<VpnServer> {
        val raw = try {
            download(subUrl)
        } catch (e: Exception) {
            Log.e(TAG, "download failed: ${e.message}")
            throw e
        }

        val decoded = decodeIfBase64(raw)

        // Extract lines and also scan for inline links anywhere in the text
        val lines = decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val protocolRegex = Regex("(?i)(vless://|vmess://|trojan://|ss://)[^\\s]+")
        val found = mutableListOf<String>()

        // find explicit protocol occurrences
        for (line in lines) {
            protocolRegex.findAll(line).forEach { m ->
                found.add(m.value)
            }
        }

        // fallback: if nothing found, try original raw lines that look like links
        if (found.isEmpty()) {
            for (line in lines) {
                if (line.startsWith("vless://") || line.startsWith("vmess://") || line.startsWith("trojan://") || line.startsWith("ss://")) {
                    found.add(line)
                }
            }
        }

        val result = mutableListOf<VpnServer>()
        var idCounter = 1
        for (link in found) {
            try {
                val (flag, name, proto) = describeLink(link)
                result.add(VpnServer(id = idCounter++, flag = flag, name = name, protocolLabel = proto, link = link, pingMs = null))
            } catch (e: Exception) {
                Log.w(TAG, "skipping link due to parse error: ${e.message}")
            }
        }

        return result
    }

    private fun download(urlStr: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SchnellVPN/1.0")
            // Some subscription endpoints require Host header or others; keep minimal for now
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            conn.inputStream.use { input ->
                return input.bufferedReader(StandardCharsets.UTF_8).readText()
            }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun decodeIfBase64(body: String): String {
        val clean = body.trim()
        if (clean.isEmpty()) return clean
        // Heuristics: if the body contains newlines but looks like base64 (no spaces, mostly A-Za-z0-9+/=)
        val candidate = clean.replace("\n", "").replace("\r", "").trim()
        val isLikelyBase64 = candidate.length > 16 && candidate.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))
        if (!isLikelyBase64) return clean

        return try {
            val padded = padBase64(candidate)
            val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
            val text = String(decodedBytes, StandardCharsets.UTF_8)
            if (text.contains("vless://") || text.contains("vmess://") || text.contains("trojan://") || text.contains("ss://")) text else clean
        } catch (e: Exception) {
            Log.w(TAG, "base64 decode failed: ${e.message}")
            clean
        }
    }

    private fun describeLink(link: String): Triple<String, String, String> {
        return when {
            link.startsWith("vmess://") -> {
                val raw = link.removePrefix("vmess://")
                val jsonText = try { String(android.util.Base64.decode(padBase64(raw), Base64.DEFAULT)) } catch (e: Exception) { throw IllegalArgumentException("invalid vmess payload") }
                val json = JSONObject(jsonText)
                val remark = json.optString("ps").ifBlank { json.optString("add", "VMess Server") }
                val net = json.optString("net", "tcp").uppercase()
                val tls = if (json.optString("tls") == "tls") " · TLS" else ""
                val (flag, name) = splitFlag(remark)
                Triple(flag, name, "VMess · $net$tls")
            }
            else -> {
                val uri = try { URI(link) } catch (e: Exception) { throw IllegalArgumentException("malformed uri") }
                val remark = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: uri.host ?: "سرور"
                val rawQuery = uri.rawQuery ?: ""
                val params = rawQuery.split("&").mapNotNull {
                    val i = it.indexOf("=")
                    if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }.toMap()

                val proto = when {
                    link.startsWith("vless://") -> "VLESS" + when {
                        params["security"] == "reality" -> " · Reality"
                        params["type"] == "ws" -> " · WS"
                        params["type"] == "grpc" -> " · gRPC"
                        else -> ""
                    }
                    link.startsWith("trojan://") -> "Trojan" + if (params["type"] == "ws") " · WS" else ""
                    link.startsWith("ss://") -> "Shadowsocks"
                    else -> "نامشخص"
                }
                val (flag, name) = splitFlag(remark)
                Triple(flag, name, proto)
            }
        }
    }

    private fun padBase64(s: String): String {
        val clean = s.trim()
        val mod = clean.length % 4
        return if (mod == 0) clean else clean + "=".repeat(4 - mod)
    }

    private fun splitFlag(remark: String): Pair<String, String> {
        if (remark.isEmpty()) return "🌐" to "سرور"
        return try {
            val cps = remark.codePoints().toArray()
            if (cps.size >= 2 && cps[0] in 0x1F1E6..0x1F1FF && cps[1] in 0x1F1E6..0x1F1FF) {
                val flag = String(cps, 0, 2)
                val rest = String(cps, 2, cps.size - 2).trim().trim('-', '·', ' ')
                flag to rest.ifEmpty { remark }
            } else {
                "🌐" to remark
            }
        } catch (e: Exception) {
            Log.w(TAG, "splitFlag failed: ${e.message}")
            "🌐" to remark
        }
    }
}
