package com.schnellvpn.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Converts share links (vless / vmess / trojan / ss) or a ready JSON outbound
 * into a complete Xray-core config that exposes a local SOCKS inbound.
 *
 * Pure JVM + org.json only (no android.* classes) so it is unit-testable.
 */
object XrayConfigBuilder {

    fun buildConfig(link: String, socksPort: Int = 10808): String {
        val outbound = parseOutbound(link)

        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("access", ""); put("error", ""); put("loglevel", "warning")
        })

        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "socks"); put("port", socksPort); put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
                put("routeOnly", false)
            })
            put("settings", JSONObject().apply {
                put("auth", "noauth"); put("udp", true); put("allowTransparent", false)
            })
        }))

        root.put("outbounds", JSONArray().apply {
            put(outbound) // first outbound = default route
            put(JSONObject().apply {
                put("protocol", "freedom"); put("tag", "DIRECT")
                put("settings", JSONObject().put("domainStrategy", "UseIPv4"))
            })
            put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "BLOCK") })
        })

        root.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs"); put("rules", JSONArray())
        })

        return root.toString(2)
    }

    /** host/port of the remote server, or null if the link can't be parsed. */
    fun endpointOf(link: String): Pair<String, Int>? = try {
        val ob = parseOutbound(link)
        val settings = ob.optJSONObject("settings")
        val entry = when (ob.optString("protocol")) {
            "vless", "vmess" -> settings?.optJSONArray("vnext")?.optJSONObject(0)
            else -> settings?.optJSONArray("servers")?.optJSONObject(0)
        }
        val host = entry?.optString("address").orEmpty()
        val port = entry?.optInt("port", 0) ?: 0
        if (host.isNotEmpty() && port in 1..65535) host to port else null
    } catch (e: Exception) {
        null
    }

    /** Throws IllegalArgumentException with a readable message for bad input. */
    fun parseOutbound(link: String): JSONObject {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Link is empty")

        return try {
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).apply { put("tag", "proxy") }
            } else when {
                trimmed.startsWith("vless://", true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", true) -> parseShadowsocks(trimmed)
                else -> throw IllegalArgumentException("Unsupported protocol: ${trimmed.take(20)}")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid server config: ${e.message}")
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid link: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- URL helpers

    internal class ParsedUrl(
        val userInfo: String?,
        val host: String,
        val port: Int?,
        val params: Map<String, String>,
        val fragment: String
    )

    /** Tolerant parser: java.net.URI rejects spaces / unicode / '|' in real-world links. */
    internal fun parseUrl(link: String): ParsedUrl {
        val schemeEnd = link.indexOf("://")
        require(schemeEnd > 0) { "Invalid link" }
        var rest = link.substring(schemeEnd + 3)

        val fragment = decodeUrl(rest.substringAfter('#', ""))
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')
        val authority = rest.substringBefore('/')

        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) decodeUserInfo(authority.substring(0, at)) else null
        val (host, port) = splitHostPort(if (at >= 0) authority.substring(at + 1) else authority)
        require(host.isNotEmpty()) { "Missing host" }

        return ParsedUrl(userInfo, host, port, parseQuery(query), fragment)
    }

    internal fun splitHostPort(hostPort: String): Pair<String, Int?> {
        val s = hostPort.trim()
        if (s.startsWith("[")) { // [IPv6]:port
            val close = s.indexOf(']')
            require(close > 0) { "Bad IPv6 address" }
            val host = s.substring(1, close)
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull()
            return host to port
        }
        val firstColon = s.indexOf(':')
        if (firstColon < 0) return s to null
        if (firstColon != s.lastIndexOf(':')) return s to null // bare IPv6, no port
        return s.substring(0, firstColon) to s.substring(firstColon + 1).toIntOrNull()
    }

    internal fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            map.putIfAbsent(decodeUrl(pair.substring(0, idx)), decodeUrl(pair.substring(idx + 1)))
        }
        return map
    }

    internal fun decodeUrl(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** '+' is a literal in UUIDs/passwords, unlike in query strings. */
    private fun decodeUserInfo(s: String): String = decodeUrl(s.replace("+", "%2B"))

    private fun validPort(port: Int?, default: Int = 443): Int {
        val p = port ?: default
        require(p in 1..65535) { "Invalid port: $p" }
        return p
    }

    // ---------------------------------------------------------------- stream settings

    /**
     * params use the vless-style query keys: type, security, sni, fp, alpn, path, host,
     * serviceName, mode, headerType, pbk, sid, spx, seed, extra.
     */
    private fun buildStream(p: Map<String, String>, address: String, defaultSecurity: String): JSONObject {
        val network = (p["type"] ?: "tcp").lowercase().let { if (it == "splithttp") "xhttp" else it }
        val security = (p["security"] ?: defaultSecurity).lowercase()
        val host = p["host"].orEmpty()
        val path = p["path"] ?: "/"

        return JSONObject().apply {
            put("network", network)
            put("security", security)

            when (network) {
                "tcp" -> if (p["headerType"] == "http") {
                    put("tcpSettings", JSONObject().put("header", JSONObject().apply {
                        put("type", "http")
                        put("request", JSONObject().apply {
                            put("version", "1.1"); put("method", "GET")
                            put("path", JSONArray().apply { path.split(",").forEach { put(it.trim()) } })
                            put("headers", JSONObject().apply {
                                put("Host", JSONArray().apply {
                                    (host.ifEmpty { address }).split(",").forEach { put(it.trim()) }
                                })
                                put("User-Agent", JSONArray())
                                put("Accept-Encoding", JSONArray().put("gzip, deflate"))
                                put("Connection", JSONArray().put("keep-alive"))
                                put("Pragma", JSONArray().put("no-cache"))
                            })
                        })
                    }))
                }
                "kcp" -> put("kcpSettings", JSONObject().apply {
                    put("header", JSONObject().put("type", p["headerType"] ?: "none"))
                    p["seed"]?.let { put("seed", it) }
                })
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", path)
                    if (host.isNotEmpty()) put("headers", JSONObject().put("Host", host))
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", p["serviceName"] ?: "")
                    put("multiMode", p["mode"] == "multi")
                    p["authority"]?.let { put("authority", it) }
                })
                "h2", "http" -> put("httpSettings", JSONObject().apply {
                    put("path", path)
                    put("host", JSONArray().put(host.ifEmpty { address }))
                })
                "httpupgrade" -> put("httpupgradeSettings", JSONObject().apply {
                    put("path", path)
                    if (host.isNotEmpty()) put("host", host)
                })
                "xhttp" -> put("xhttpSettings", JSONObject().apply {
                    put("path", path)
                    if (host.isNotEmpty()) put("host", host)
                    put("mode", p["mode"] ?: "auto")
                    p["extra"]?.let { runCatching { put("extra", JSONObject(it)) } }
                })
                else -> throw IllegalArgumentException("Unsupported transport: $network")
            }

            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", p["sni"] ?: host.ifEmpty { address })
                    put("allowInsecure", false)
                    put("fingerprint", p["fp"] ?: "chrome")
                    p["alpn"]?.takeIf { it.isNotBlank() }?.let { a ->
                        put("alpn", JSONArray().apply { a.split(",").forEach { put(it.trim()) } })
                    }
                })
                "reality" -> {
                    val pbk = p["pbk"].orEmpty()
                    if (pbk.isEmpty()) throw IllegalArgumentException("Reality link is missing the public key (pbk)")
                    put("realitySettings", JSONObject().apply {
                        put("serverName", p["sni"] ?: address)
                        put("fingerprint", p["fp"] ?: "chrome")
                        put("publicKey", pbk)
                        put("shortId", p["sid"] ?: "")
                        put("spiderX", p["spx"] ?: "/")
                    })
                }
                "none", "" -> {}
                else -> throw IllegalArgumentException("Unsupported security: $security")
            }
        }
    }

    private fun defaultMux() = JSONObject().apply { put("enabled", false); put("concurrency", -1) }

    // ---------------------------------------------------------------- protocols

    private fun parseVless(link: String): JSONObject {
        val u = parseUrl(link)
        val uuid = u.userInfo?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing UUID")
        val port = validPort(u.port)
        val flow = u.params["flow"].orEmpty()

        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", u.host); put("port", port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid); put("encryption", u.params["encryption"] ?: "none")
                    if (flow.isNotEmpty()) put("flow", flow)
                }))
            })))
            put("streamSettings", buildStream(u.params, u.host, "none"))
            put("mux", defaultMux())
        }
    }

    internal fun decodeVmessJson(link: String): JSONObject {
        val raw = link.substring("vmess://".length).substringBefore('#').trim()
        val json = Base64Compat.decodeToString(decodeUrl(raw).replace(" ", "+"))
            ?: throw IllegalArgumentException("Invalid vmess Base64")
        return JSONObject(json)
    }

    private fun parseVmess(link: String): JSONObject {
        val obj = decodeVmessJson(link)
        val address = obj.optString("add", "")
        if (address.isEmpty()) throw IllegalArgumentException("Missing host")
        val id = obj.optString("id", "")
        if (id.isEmpty()) throw IllegalArgumentException("Missing UUID")
        val port = validPort(obj.optString("port", "443").toIntOrNull())

        val net = obj.optString("net", "tcp").ifEmpty { "tcp" }
        val p = HashMap<String, String>()
        p["type"] = net
        p["security"] = if (obj.optString("tls").equals("tls", true)) "tls" else "none"
        obj.optString("host").takeIf { it.isNotEmpty() }?.let { p["host"] = it }
        obj.optString("path").takeIf { it.isNotEmpty() }?.let {
            p["path"] = it
            if (net == "grpc") p["serviceName"] = it
            if (net == "kcp") p["seed"] = it
        }
        obj.optString("sni").takeIf { it.isNotEmpty() }?.let { p["sni"] = it }
        obj.optString("fp").takeIf { it.isNotEmpty() }?.let { p["fp"] = it }
        obj.optString("alpn").takeIf { it.isNotEmpty() }?.let { p["alpn"] = it }
        obj.optString("type").takeIf { it.isNotEmpty() && it != "none" }?.let {
            p["headerType"] = it
            if (net == "grpc") p["mode"] = it
        }

        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "vmess")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", address); put("port", port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", id)
                    put("alterId", obj.optString("aid", "0").toIntOrNull() ?: 0)
                    put("security", obj.optString("scy", "auto").ifEmpty { "auto" })
                }))
            })))
            put("streamSettings", buildStream(p, address, "none"))
            put("mux", defaultMux())
        }
    }

    private fun parseTrojan(link: String): JSONObject {
        val u = parseUrl(link)
        val password = u.userInfo?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Missing password")
        val port = validPort(u.port)
        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "trojan")
            put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                put("address", u.host); put("port", port); put("password", password)
            })))
            put("streamSettings", buildStream(u.params, u.host, "tls"))
        }
    }

    private fun parseShadowsocks(link: String): JSONObject {
        var body = link.substring("ss://".length).substringBefore('#')
        val query = body.substringAfter('?', "")
        body = body.substringBefore('?')
        if (parseQuery(query).containsKey("plugin")) {
            throw IllegalArgumentException("Shadowsocks plugins are not supported")
        }

        val userInfo: String
        val hostPort: String
        if (body.contains('@')) {
            // SIP002: ss://BASE64(method:password)@host:port  (or plain method:password@host:port)
            val ui = decodeUserInfo(body.substringBeforeLast('@'))
            userInfo = if (ui.contains(':')) ui else (Base64Compat.decodeToString(ui) ?: ui)
            hostPort = body.substringAfterLast('@').trimEnd('/')
        } else {
            // legacy: ss://BASE64(method:password@host:port)
            val decoded = Base64Compat.decodeToString(decodeUrl(body))
                ?: throw IllegalArgumentException("Invalid Shadowsocks link")
            userInfo = decoded.substringBeforeLast('@')
            hostPort = decoded.substringAfterLast('@', "")
        }

        val method = userInfo.substringBefore(':', "")
        val password = userInfo.substringAfter(':', "")
        if (method.isEmpty() || password.isEmpty()) throw IllegalArgumentException("Invalid Shadowsocks credentials")
        val (host, port) = splitHostPort(hostPort)
        if (host.isEmpty()) throw IllegalArgumentException("Missing host")

        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "shadowsocks")
            put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                put("address", host); put("port", validPort(port)); put("method", method); put("password", password)
            })))
        }
    }
}
