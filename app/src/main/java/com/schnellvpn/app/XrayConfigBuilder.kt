package com.schnellvpn.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object XrayConfigBuilder {

    fun buildConfig(link: String, socksPort: Int = 10808): String {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Link is empty")

        // اگه link یه JSON outbound کامله (از SubscriptionFetcher)، مستقیم استفاده کن
        val outbound = if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            obj.put("tag", "proxy")
            obj
        } else {
            parseLinkToOutbound(trimmed)
        }

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
            put(outbound)
            put(JSONObject().apply { put("protocol", "freedom"); put("tag", "DIRECT")
                put("settings", JSONObject().put("domainStrategy", "UseIPv4")) })
            put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "BLOCK") })
        })

        root.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs"); put("rules", JSONArray())
        })

        return root.toString(2)
    }

    private fun parseLinkToOutbound(link: String): JSONObject = when {
        link.startsWith("vless://")  -> parseVless(link)
        link.startsWith("vmess://")  -> parseVmess(link)
        link.startsWith("trojan://") -> parseTrojan(link)
        link.startsWith("ss://")     -> parseShadowsocks(link)
        else -> throw IllegalArgumentException("Unsupported protocol: ${link.take(20)}")
    }

    private fun parseVless(link: String): JSONObject {
        val uri = URI(link)
        val uuid = uri.userInfo ?: throw IllegalArgumentException("Missing UUID")
        val address = uri.host ?: throw IllegalArgumentException("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)
        val network = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val flow = params["flow"] ?: ""

        val ss = JSONObject().apply {
            put("network", network); put("security", security)
            when (network) {
                "tcp" -> if (params["headerType"] == "http") put("tcpSettings", JSONObject().put("header", JSONObject().apply {
                    put("type", "http")
                    put("request", JSONObject().apply {
                        put("version", "1.1"); put("method", "GET")
                        put("path", JSONArray().put(params["path"] ?: "/"))
                        put("headers", JSONObject().apply {
                            put("Host", JSONArray().put(params["host"] ?: address))
                            put("User-Agent", JSONArray()); put("Accept-Encoding", JSONArray().put("gzip, deflate"))
                            put("Connection", JSONArray().put("keep-alive")); put("Pragma", "no-cache")
                        })
                    })
                }))
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", params["path"] ?: "/")
                    put("headers", JSONObject().put("Host", params["host"] ?: address))
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", params["serviceName"] ?: ""); put("multiMode", false)
                })
                "h2", "http" -> put("httpSettings", JSONObject().apply {
                    put("path", params["path"] ?: "/")
                    put("host", JSONArray().put(params["host"] ?: address))
                })
            }
            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", params["sni"] ?: address); put("allowInsecure", false)
                    put("fingerprint", params["fp"] ?: "chrome")
                    params["alpn"]?.let { put("alpn", JSONArray().apply { it.split(",").forEach { a -> put(a.trim()) } }) }
                })
                "reality" -> put("realitySettings", JSONObject().apply {
                    put("serverName", params["sni"] ?: address)
                    put("fingerprint", params["fp"] ?: "chrome")
                    put("publicKey", params["pbk"] ?: ""); put("shortId", params["sid"] ?: ""); put("spiderX", params["spx"] ?: "/")
                })
            }
        }

        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", address); put("port", port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid); put("encryption", "none")
                    if (flow.isNotEmpty()) put("flow", flow)
                }))
            })))
            put("streamSettings", ss)
            put("mux", JSONObject().apply { put("enabled", false); put("concurrency", -1) })
        }
    }

    private fun parseVmess(link: String): JSONObject {
        val raw = link.removePrefix("vmess://")
        val json = String(Base64.decode(padBase64(raw), Base64.DEFAULT))
        val obj = JSONObject(json)
        val address = obj.optString("add", "")
        val network = obj.optString("net", "tcp")
        val security = if (obj.optString("tls") == "tls") "tls" else "none"
        val ss = JSONObject().apply {
            put("network", network); put("security", security)
            when (network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", obj.optString("path", "/"))
                    put("headers", JSONObject().put("Host", obj.optString("host", address)))
                })
                "grpc" -> put("grpcSettings", JSONObject().put("serviceName", obj.optString("serviceName", "")))
                "h2" -> put("httpSettings", JSONObject().apply {
                    put("path", obj.optString("path", "/")); put("host", JSONArray().put(obj.optString("host", address)))
                })
            }
            if (security == "tls") put("tlsSettings", JSONObject().apply {
                put("serverName", obj.optString("sni", address)); put("allowInsecure", false)
                put("fingerprint", obj.optString("fp", "chrome"))
            })
        }
        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "vmess")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", address); put("port", obj.optString("port", "443").toIntOrNull() ?: 443)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", obj.optString("id", "")); put("alterId", obj.optString("aid", "0").toIntOrNull() ?: 0); put("security", "auto")
                }))
            }))); put("streamSettings", ss)
            put("mux", JSONObject().apply { put("enabled", false); put("concurrency", -1) })
        }
    }

    private fun parseTrojan(link: String): JSONObject {
        val uri = URI(link)
        val password = uri.userInfo ?: ""; val address = uri.host ?: ""; val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery); val network = params["type"] ?: "tcp"
        val ss = JSONObject().apply {
            put("network", network); put("security", params["security"] ?: "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", params["sni"] ?: address); put("allowInsecure", false); put("fingerprint", params["fp"] ?: "chrome")
            })
            if (network == "ws") put("wsSettings", JSONObject().apply {
                put("path", params["path"] ?: "/"); put("headers", JSONObject().put("Host", params["host"] ?: address))
            })
            if (network == "grpc") put("grpcSettings", JSONObject().put("serviceName", params["serviceName"] ?: ""))
        }
        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "trojan")
            put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                put("address", address); put("port", port); put("password", password)
            }))); put("streamSettings", ss)
        }
    }

    private fun parseShadowsocks(link: String): JSONObject {
        val body = link.removePrefix("ss://").substringBefore("#"); val atIndex = body.lastIndexOf('@')
        val userInfoRaw = if (atIndex >= 0) body.substring(0, atIndex) else ""
        val hostPort = if (atIndex >= 0) body.substring(atIndex + 1) else body
        val userInfo = if (userInfoRaw.contains(":")) userInfoRaw
        else try { String(Base64.decode(padBase64(userInfoRaw), Base64.DEFAULT)) } catch (_: Exception) { userInfoRaw }
        val parts = userInfo.split(":", limit = 2)
        val method = parts.getOrElse(0) { "aes-256-gcm" }; val password = parts.getOrElse(1) { "" }
        val hpParts = hostPort.substringBefore("?").split(":", limit = 2)
        val address = hpParts.getOrElse(0) { "" }; val port = hpParts.getOrElse(1) { "443" }.toIntOrNull() ?: 443
        return JSONObject().apply {
            put("tag", "proxy"); put("protocol", "shadowsocks")
            put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
                put("address", address); put("port", port); put("method", method); put("password", password)
            })))
        }
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("="); if (idx < 0) null
            else pair.substring(0, idx) to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun padBase64(s: String): String {
        val mod = s.length % 4; return if (mod == 0) s else s + "=".repeat(4 - mod)
    }
}
