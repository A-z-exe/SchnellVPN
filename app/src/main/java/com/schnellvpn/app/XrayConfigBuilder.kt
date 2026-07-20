package com.schnellvpn.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * XrayConfigBuilder - ساخت کانفیگ صحیح برای Xray-core
 * معماری: HevBridge (TUN/Layer3) → Socks5 (Layer4) → Xray-core (Layer7)
 */
object XrayConfigBuilder {

    fun buildConfig(
        link: String,
        socksPort: Int = 10808
    ): String {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Link is empty")

        val outbound = try {
            parseLinkToOutbound(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid link format: ${e.message}")
        }

        val root = JSONObject()

        // ========== 1. LOG ==========
       root.put("log", JSONObject().apply {
             put("loglevel", "warning")
             put("access", "none")
             put("error", "none")
        })

        // ========== 2. INBOUNDS ==========
        // فقط Socks5 inbound — TUN توسط HevBridge هندل میشه
        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("port", socksPort)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                    put("routeOnly", false)
                })
            })
        }
        root.put("inbounds", inbounds)

        // ========== 3. OUTBOUNDS ==========
        val outbounds = JSONArray().apply {
            put(outbound)
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                })
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
            })
        }
        root.put("outbounds", outbounds)

        // ========== 4. ROUTING ==========
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray().apply {
                // ترافیک LAN مستقیم
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("127.0.0.0/8")
                        put("::1/128")
                        put("fc00::/7")
                    })
                    put("outboundTag", "direct")
                })
                // همه بقیه ترافیک از پروکسی
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        // ========== 5. DNS ==========
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("8.8.8.8")
                put("1.1.1.1")
                put("localhost")
            })
        })

        // ========== 6. POLICY ==========
        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("0", JSONObject().apply {
                    put("connIdle", 300)
                    put("uplinkOnly", 5)
                    put("downlinkOnly", 30)
                    put("bufferSize", 10240)
                })
            })
        })

        return root.toString(2)
    }

    // ============================================================
    // PARSE LINK TO OUTBOUND
    // ============================================================

    private fun parseLinkToOutbound(link: String): JSONObject = when {
        link.startsWith("vless://") -> parseVless(link)
        link.startsWith("vmess://") -> parseVmess(link)
        link.startsWith("trojan://") -> parseTrojan(link)
        link.startsWith("ss://") -> parseShadowsocks(link)
        else -> throw IllegalArgumentException("Unsupported protocol: ${link.take(20)}")
    }

    // ==================== VLESS ====================
    private fun parseVless(link: String): JSONObject {
        val uri = try { URI(link) } catch (e: Exception) {
            throw IllegalArgumentException("Malformed vless URI: ${e.message}")
        }
        val uuid = uri.userInfo ?: throw IllegalArgumentException("Missing UUID in vless link")
        val address = uri.host ?: throw IllegalArgumentException("Missing host in vless link")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)

        val network = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val flow = params["flow"] ?: ""

        val streamSettings = JSONObject().apply {
            put("network", network)
            put("security", security)

            when (network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", params["path"] ?: "/")
                    put("headers", JSONObject().apply {
                        put("Host", params["host"] ?: address)
                    })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", params["serviceName"] ?: "")
                    put("multiMode", false)
                })
                "h2", "http" -> put("httpSettings", JSONObject().apply {
                    put("path", params["path"] ?: "/")
                    put("host", JSONArray().apply {
                        put(params["host"] ?: address)
                    })
                })
                "tcp" -> if (params["headerType"] == "http") {
                    put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                        })
                    })
                }
            }

            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", params["sni"] ?: address)
                    put("allowInsecure", false)
                    put("fingerprint", params["fp"] ?: "chrome")
                    params["alpn"]?.let { alpn ->
                        put("alpn", JSONArray().apply {
                            alpn.split(",").forEach { put(it.trim()) }
                        })
                    }
                })
                "reality" -> put("realitySettings", JSONObject().apply {
                    put("serverName", params["sni"] ?: address)
                    put("fingerprint", params["fp"] ?: "chrome")
                    put("publicKey", params["pbk"] ?: "")
                    put("shortId", params["sid"] ?: "")
                    put("spiderX", params["spx"] ?: "/")
                })
            }
        }

        val user = JSONObject().apply {
            put("id", uuid)
            put("encryption", "none")
            if (flow.isNotEmpty()) put("flow", flow)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", address)
                        put("port", port)
                        put("users", JSONArray().put(user))
                    })
                })
            })
            put("streamSettings", streamSettings)
        }
    }

    // ==================== VMESS ====================
    private fun parseVmess(link: String): JSONObject {
        val raw = link.removePrefix("vmess://")
        val json = try {
            String(Base64.decode(padBase64(raw), Base64.DEFAULT))
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid vmess base64: ${e.message}")
        }

        val obj = try { JSONObject(json) } catch (e: Exception) {
            throw IllegalArgumentException("Vmess JSON parse failed: ${e.message}")
        }

        val address = obj.optString("add", "")
        if (address.isEmpty()) throw IllegalArgumentException("Missing 'add' in vmess config")

        val network = obj.optString("net", "tcp")
        val security = if (obj.optString("tls") == "tls") "tls" else "none"

        val streamSettings = JSONObject().apply {
            put("network", network)
            put("security", security)

            when (network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", obj.optString("path", "/"))
                    put("headers", JSONObject().apply {
                        put("Host", obj.optString("host", address))
                    })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", obj.optString("serviceName", ""))
                })
                "h2" -> put("httpSettings", JSONObject().apply {
                    put("path", obj.optString("path", "/"))
                    put("host", JSONArray().apply {
                        put(obj.optString("host", address))
                    })
                })
            }

            if (security == "tls") {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", obj.optString("sni", address))
                    put("allowInsecure", false)
                    put("fingerprint", obj.optString("fp", "chrome"))
                })
            }
        }

        val user = JSONObject().apply {
            put("id", obj.optString("id", ""))
            put("alterId", obj.optString("aid", "0").toIntOrNull() ?: 0)
            put("security", "auto")
            put("level", 8)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", address)
                        put("port", obj.optString("port", "443").toIntOrNull() ?: 443)
                        put("users", JSONArray().put(user))
                    })
                })
            })
            put("streamSettings", streamSettings)
        }
    }

    // ==================== TROJAN ====================
    private fun parseTrojan(link: String): JSONObject {
        val uri = try { URI(link) } catch (e: Exception) {
            throw IllegalArgumentException("Malformed trojan URI: ${e.message}")
        }
        val password = uri.userInfo ?: throw IllegalArgumentException("Missing password in trojan link")
        val address = uri.host ?: throw IllegalArgumentException("Missing host in trojan link")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)

        val network = params["type"] ?: "tcp"
        val streamSettings = JSONObject().apply {
            put("network", network)
            put("security", params["security"] ?: "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", params["sni"] ?: address)
                put("allowInsecure", false)
                put("fingerprint", params["fp"] ?: "chrome")
            })

            when (network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", params["path"] ?: "/")
                    put("headers", JSONObject().apply {
                        put("Host", params["host"] ?: address)
                    })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", params["serviceName"] ?: "")
                })
            }
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", address)
                        put("port", port)
                        put("password", password)
                    })
                })
            })
            put("streamSettings", streamSettings)
        }
    }

    // ==================== SHADOWSOCKS ====================
    private fun parseShadowsocks(link: String): JSONObject {
        val body = link.removePrefix("ss://").substringBefore("#")
        val atIndex = body.lastIndexOf('@')

        val userInfoRaw = if (atIndex >= 0) body.substring(0, atIndex) else ""
        val hostPort = if (atIndex >= 0) body.substring(atIndex + 1) else body

        val userInfo = if (userInfoRaw.contains(":")) {
            userInfoRaw
        } else {
            try { String(Base64.decode(padBase64(userInfoRaw), Base64.DEFAULT)) }
            catch (_: Exception) { userInfoRaw }
        }

        val parts = userInfo.split(":", limit = 2)
        val method = parts.getOrElse(0) { "aes-256-gcm" }
        val password = parts.getOrElse(1) { "" }

        val hpParts = hostPort.substringBefore("?").split(":", limit = 2)
        val address = hpParts.getOrElse(0) { throw IllegalArgumentException("Missing host in ss link") }
        val port = hpParts.getOrElse(1) { "443" }.toIntOrNull() ?: 443

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", address)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                        put("level", 8)
                    })
                })
            })
        }
    }

    // ============================================================
    // UTILITY
    // ============================================================

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) null
            else pair.substring(0, idx) to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    private fun padBase64(s: String): String {
        val mod = s.length % 4
        return if (mod == 0) s else s + "=".repeat(4 - mod)
    }
}
