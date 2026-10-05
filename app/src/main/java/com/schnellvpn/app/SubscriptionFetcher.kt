package com.schnellvpn.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a subscription and converts it to a list of [VpnServer].
 * Supported bodies: plain link list, Base64 link list, JSON array / object (v2rayN style).
 * Only servers that can actually be turned into an Xray outbound are returned.
 */
object SubscriptionFetcher {

    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun fetchAndParse(subUrl: String): List<VpnServer> = parseContent(downloadText(subUrl))

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
            if (!seen.add(link)) continue

            val outbound = try {
                XrayConfigBuilder.parseOutbound(link)
            } catch (e: Exception) {
                continue // skip links we can't turn into a working config
            }

            val name = remarkOf(link).ifEmpty {
                XrayConfigBuilder.endpointOf(link)?.first ?: "Server ${servers.size + 1}"
            }
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
            else -> outbound.optString("protocol").uppercase()
        }
    }

    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://")

    private fun startsWithKnownScheme(s: String) = schemes.any { s.startsWith(it, ignoreCase = true) }

    private fun containsKnownScheme(text: String) =
        text.lines().any { startsWithKnownScheme(it.trim()) }

    private fun tryBase64Decode(text: String): String? {
        val decoded = Base64Compat.decodeToString(text) ?: return null
        return if (containsKnownScheme(decoded)) decoded else null
    }

    // ---------------------------------------------------------------- network

    private fun downloadText(urlStr: String): String {
        var current = URL(urlStr.trim())
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
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    current = URL(current, location)
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                return readLimited(conn)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readLimited(conn: HttpURLConnection): String {
        conn.inputStream.use { input ->
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
}
