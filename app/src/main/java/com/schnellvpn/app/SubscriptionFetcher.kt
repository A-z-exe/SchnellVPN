package com.schnellvpn.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object SubscriptionFetcher {

    fun fetchAndParse(subUrl: String): List<VpnServer> {
        val raw = downloadText(subUrl).trim()

        // فرمت JSON کامل (v2rayN full config) — آرایه‌ای از کانفیگ‌های کامل
        if (raw.startsWith("[")) {
            return parseJsonArray(raw)
        }

        // فرمت سنتی — لینک‌های vless:// vmess:// trojan:// ss:// (احتمالاً base64)
        val decoded = tryBase64Decode(raw) ?: raw
        return parseLinks(decoded)
    }

    // ==================== JSON Array (v2rayN full config) ====================
    private fun parseJsonArray(json: String): List<VpnServer> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val servers = mutableListOf<VpnServer>()

        for (i in 0 until arr.length()) {
            val obj = try { arr.getJSONObject(i) } catch (e: Exception) { continue }
            val remarks = obj.optString("remarks", "")

            val outbounds = obj.optJSONArray("outbounds") ?: continue

            // پیدا کردن outbound که tag=proxy داره
            var proxy: JSONObject? = null
            for (j in 0 until outbounds.length()) {
                val ob = try { outbounds.getJSONObject(j) } catch (e: Exception) { continue }
                if (ob.optString("tag") == "proxy") { proxy = ob; break }
            }
            if (proxy == null && outbounds.length() > 0) {
                proxy = try { outbounds.getJSONObject(0) } catch (e: Exception) { null }
            }
            proxy ?: continue

            val protocol = proxy.optString("protocol", "")
            val ss = proxy.optJSONObject("streamSettings")
            val network = ss?.optString("network", "tcp") ?: "tcp"
            val security = ss?.optString("security", "none") ?: "none"

            val protocolLabel = when (protocol) {
                "vless" -> when {
                    security == "reality" -> "VLESS · Reality"
                    network == "ws" -> "VLESS · WS"
                    network == "grpc" -> "VLESS · gRPC"
                    network == "xhttp" -> "VLESS · XHTTP"
                    else -> "VLESS"
                }
                "vmess"       -> "VMess"
                "trojan"      -> "Trojan"
                "shadowsocks" -> "Shadowsocks"
                else          -> protocol.uppercase()
            }

            val address = extractAddress(proxy)
            val name = remarks.ifEmpty { address.ifEmpty { "Server ${i + 1}" } }

            // کل JSON outbound رو به عنوان link ذخیره می‌کنیم — XrayConfigBuilder مستقیم ازش استفاده می‌کنه
            servers.add(VpnServer(
                id = i + 1,
                flag = "🌐",
                name = name,
                protocolLabel = protocolLabel,
                link = proxy.toString(),
                pingMs = null
            ))
        }
        return servers
    }

    private fun extractAddress(outbound: JSONObject): String {
        val settings = outbound.optJSONObject("settings") ?: return ""
        return when (outbound.optString("protocol")) {
            "vless", "vmess" -> settings.optJSONArray("vnext")?.optJSONObject(0)?.optString("address") ?: ""
            "trojan", "shadowsocks" -> settings.optJSONArray("servers")?.optJSONObject(0)?.optString("address") ?: ""
            else -> ""
        }
    }

    // ==================== لینک‌های سنتی vless:// vmess:// ====================
    private fun parseLinks(text: String): List<VpnServer> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") }

        return lines.mapIndexedNotNull { index, link ->
            try {
                val scheme = link.substringBefore("://")
                val remark = runCatching {
                    URLDecoder.decode(link.substringAfter("#", ""), "UTF-8").trim()
                }.getOrDefault("")
                val name = remark.ifEmpty { "Server ${index + 1}" }

                val protocolLabel = when (scheme) {
                    "vless" -> if (link.contains("security=reality")) "VLESS · Reality" else "VLESS"
                    "vmess" -> "VMess"
                    "trojan" -> "Trojan"
                    "ss" -> "Shadowsocks"
                    else -> scheme.uppercase()
                }

                VpnServer(id = index + 1, flag = "🌐", name = name, protocolLabel = protocolLabel, link = link, pingMs = null)
            } catch (e: Exception) { null }
        }
    }

    private fun downloadText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "SchnellVPN/1.0")
        return try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
    }

    private fun tryBase64Decode(text: String): String? {
        return try {
            val clean = text.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            val result = String(Base64.decode(clean, Base64.DEFAULT))
            if (result.contains("://")) result else null
        } catch (e: Exception) { null }
    }
}
