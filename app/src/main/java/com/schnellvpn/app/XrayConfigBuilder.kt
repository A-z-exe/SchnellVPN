package com.schnellvpn.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * XrayConfigBuilder - بازنویسی کامل با ساختار استاندارد Xray-core
 * پشتیبانی از: VLESS, VMess, Trojan, Shadowsocks
 * بهینه‌شده برای TUN-mode و Routing صحیح
 */
object XrayConfigBuilder {

    /**
     * ساخت کانفیگ کامل Xray-core
     * @param link لینک ورودی (vless://, vmess://, trojan://, ss://)
     * @param socksPort پورت Socks proxy داخلی
     * @param tunFd فایل دیسکریپتور TUN (برای TUN-mode)
     */
    fun buildConfig(
        link: String,
        socksPort: Int = 10808,
        tunFd: Int = -1
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
            put("loglevel", "warning")  // برای دیباگ: "debug" بگذارید
            put("access", "/sdcard/xray_access.log")
            put("error", "/sdcard/xray_error.log")
        })

        // ========== 2. INBOUNDS ==========
        val inbounds = JSONArray().apply {
            // Socks5 inbound برای تست و دیباگ
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
                        put("quic")
                    })
                })
            })
            
            // TUN inbound (اگر tunFd معتبر باشد)
            if (tunFd > 0) {
                put(JSONObject().apply {
                    put("tag", "tun-in")
                    put("protocol", "tun")
                    put("settings", JSONObject().apply {
                        put("address", JSONArray().apply {
                            put("10.0.0.2/32")   // IPv4
                            put("fd00::2/64")     // IPv6 (اختیاری)
                        })
                        put("mtu", 1500)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply {
                            put("http")
                            put("tls")
                            put("quic")
                        })
                    })
                })
            }
        }
        root.put("inbounds", inbounds)

        // ========== 3. OUTBOUNDS ==========
        val outbounds = JSONArray().apply {
            // خروجی اصلی (پروکسی)
            put(outbound)
            
            // خروجی مستقیم (برای ترافیک داخلی)
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIP")  // مهم برای DNS
                })
            })
            
            // خروجی بلاک (برای مسدودسازی)
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply {
                        put("type", "none")
                    })
                })
            })
            
            // DNS خروجی
            put(JSONObject().apply {
                put("tag", "dns-out")
                put("protocol", "dns")
            })
        }
        root.put("outbounds", outbounds)

        // ========== 4. ROUTING (بسیار مهم) ==========
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")  // یا "IPOnDemand"
            
            val rules = JSONArray().apply {
                // ====== Rule 1: DNS ======
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", "53")
                    put("network", "udp")
                    put("outboundTag", "dns-out")
                })
                
                // ====== Rule 2: ترافیک LAN ======
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                        put("geoip:loopback")
                    })
                    put("outboundTag", "direct")
                })
                
                // ====== Rule 3: همه ترافیک IPv4 (مهم) ======
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("0.0.0.0/0")  // همه IPv4
                    })
                    put("outboundTag", "proxy")
                })
                
                // ====== Rule 4: همه ترافیک IPv6 (مهم) ======
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("::/0")  // همه IPv6
                    })
                    put("outboundTag", "proxy")
                })
                
                // ====== Rule 5: استثنای بعضی دامنه‌ها (اختیاری) ======
                put(JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ads")  // مسدودسازی تبلیغات
                        put("geosite:malware")        // مسدودسازی بدافزارها
                    })
                    put("outboundTag", "block")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        // ========== 5. POLICY ==========
        val policy = JSONObject().apply {
            put("levels", JSONObject().apply {
                put("0", JSONObject().apply {
                    put("connIdle", 300)
                    put("uplinkOnly", 5)
                    put("downlinkOnly", 30)
                    put("statsUserUplink", false)
                    put("statsUserDownlink", false)
                    put("bufferSize", 10240)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", false)
                put("statsInboundDownlink", false)
                put("statsOutboundUplink", false)
                put("statsOutboundDownlink", false)
            })
        }
        root.put("policy", policy)

        // ========== 6. DNS ==========
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", "https+local://dns.google/dns-query")
                    put("domains", JSONArray().apply {
                        put("geosite:google")
                    })
                    put("expectIPs", JSONArray().apply {
                        put("geoip:google")
                    })
                })
                put(JSONObject().apply {
                    put("address", "1.1.1.1")
                    put("port", 53)
                    put("domains", JSONArray().apply {
                        put("geosite:cloudflare")
                    })
                })
                put("8.8.8.8")  // DNS پیش‌فرض
                put("1.1.1.1")
                put("https+local://dns.quad9.net/dns-query")
                put("localhost")  // DNS محلی
            })
        }
        root.put("dns", dns)

        return root.toString(2)  // Pretty print برای دیباگ
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
        try { UUID.fromString(uuid) } catch (_: Exception) { /* برخی providers از فرمت بدون خط تیره استفاده می‌کنند */ }

        val address = uri.host ?: throw IllegalArgumentException("Missing host in vless link")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)

        val network = params["type"] ?: "tcp"
        val streamSettings = JSONObject().apply {
            put("network", network)
        }

        // ===== Stream Settings =====
        when (network) {
            "ws" -> streamSettings.put("wsSettings", JSONObject().apply {
                put("path", params["path"] ?: "/")
                put("headers", JSONObject().apply {
                    put("Host", params["host"] ?: address)
                })
            })
            "grpc" -> streamSettings.put("grpcSettings", JSONObject().apply {
                put("serviceName", params["serviceName"] ?: "")
            })
            "http" -> streamSettings.put("httpSettings", JSONObject().apply {
                put("path", params["path"] ?: "/")
                put("host", JSONArray().apply {
                    put(params["host"] ?: address)
                })
            })
        }

        // ===== Security =====
        val security = params["security"] ?: "none"
        streamSettings.put("security", security)
        
        when (security) {
            "tls" -> streamSettings.put("tlsSettings", JSONObject().apply {
                put("serverName", params["sni"] ?: address)
                put("allowInsecure", false)
                put("fingerprint", params["fp"] ?: "chrome")
            })
            "reality" -> streamSettings.put("realitySettings", JSONObject().apply {
                put("serverName", params["sni"] ?: address)
                put("fingerprint", params["fp"] ?: "chrome")
                put("publicKey", params["pbk"] ?: "")
                put("shortId", params["sid"] ?: "")
                put("spiderX", params["spx"] ?: "/")
            })
        }

        // ===== XTLS Flow =====
        val flow = params["flow"] ?: ""
        if (flow.isNotEmpty() && security == "reality") {
            streamSettings.put("xtlsSettings", JSONObject().apply {
                put("flow", flow)
            })
        }

        // ===== User & Server =====
        val user = JSONObject().apply {
            put("id", uuid)
            put("encryption", "none")
            if (flow.isNotEmpty()) put("flow", flow)
        }

        val server = JSONObject().apply {
            put("address", address)
            put("port", port)
            put("users", JSONArray().put(user))
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(server))
            })
            put("streamSettings", streamSettings)
        }
    }

    // ==================== VMESS ====================
    private fun parseVmess(link: String): JSONObject {
        val raw = link.removePrefix("vmess://")
        val json = try {
            val padded = padBase64(raw)
            String(Base64.decode(padded, Base64.DEFAULT))
        } catch (e: Exception) { 
            throw IllegalArgumentException("Invalid vmess base64: ${e.message}") 
        }

        val obj = try { JSONObject(json) } catch (e: Exception) { 
            throw IllegalArgumentException("Vmess JSON parse failed: ${e.message}") 
        }

        val address = obj.optString("add", "")
        if (address.isEmpty()) throw IllegalArgumentException("Missing 'add' in vmess config")

        val network = obj.optString("net", "tcp")
        val streamSettings = JSONObject().apply {
            put("network", network)
        }

        when (network) {
            "ws" -> streamSettings.put("wsSettings", JSONObject().apply {
                put("path", obj.optString("path", "/"))
                put("headers", JSONObject().apply {
                    put("Host", obj.optString("host", address))
                })
            })
            "grpc" -> streamSettings.put("grpcSettings", JSONObject().apply {
                put("serviceName", obj.optString("serviceName", ""))
            })
            "h2" -> streamSettings.put("httpSettings", JSONObject().apply {
                put("path", obj.optString("path", "/"))
                put("host", JSONArray().apply {
                    put(obj.optString("host", address))
                })
            })
        }

        // ===== Security =====
        val security = if (obj.optString("tls") == "tls") "tls" else "none"
        streamSettings.put("security", security)
        
        if (security == "tls") {
            streamSettings.put("tlsSettings", JSONObject().apply {
                put("serverName", obj.optString("sni", address))
                put("allowInsecure", false)
                put("fingerprint", obj.optString("fp", "chrome"))
            })
        }

        // ===== User =====
        val user = JSONObject().apply {
            put("id", obj.optString("id", ""))
            put("alterId", obj.optInt("aid", 0))
            put("security", "auto")
            put("level", 8)
        }

        val server = JSONObject().apply {
            put("address", address)
            put("port", obj.optString("port").toIntOrNull() ?: 443)
            put("users", JSONArray().put(user))
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(server))
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

        val streamSettings = JSONObject().apply {
            put("network", params["type"] ?: "tcp")
            put("security", params["security"] ?: "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", params["sni"] ?: address)
                put("allowInsecure", false)
                put("fingerprint", params["fp"] ?: "chrome")
            })
        }

        // ===== WS/GRPC support for Trojan =====
        when (params["type"]) {
            "ws" -> streamSettings.put("wsSettings", JSONObject().apply {
                put("path", params["path"] ?: "/")
                put("headers", JSONObject().apply {
                    put("Host", params["host"] ?: address)
                })
            })
            "grpc" -> streamSettings.put("grpcSettings", JSONObject().apply {
                put("serviceName", params["serviceName"] ?: "")
            })
        }

        val server = JSONObject().apply {
            put("address", address)
            put("port", port)
            put("password", password)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(server))
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

        // Decode user info
        val userInfo = if (userInfoRaw.isNotEmpty() && userInfoRaw.contains(":")) {
            userInfoRaw
        } else if (userInfoRaw.isNotEmpty()) {
            try { 
                String(Base64.decode(padBase64(userInfoRaw), Base64.DEFAULT)) 
            } catch (_: Exception) { 
                userInfoRaw 
            }
        } else ""

        val parts = userInfo.split(":", limit = 2)
        val method = parts.getOrElse(0) { "aes-256-gcm" }
        val password = parts.getOrElse(1) { "" }

        val cleanHostPort = hostPort.substringBefore("?")
        val hpParts = cleanHostPort.split(":", limit = 2)
        val address = hpParts.getOrElse(0) { throw IllegalArgumentException("Missing host in ss link") }
        val port = hpParts.getOrElse(1) { "443" }.toIntOrNull() ?: 443

        val server = JSONObject().apply {
            put("address", address)
            put("port", port)
            put("method", method)
            put("password", password)
            put("level", 8)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(server))
            })
        }
    }

    // ============================================================
    // UTILITY FUNCTIONS
    // ============================================================

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) null
            else {
                val key = pair.substring(0, idx)
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }
        }.toMap()
    }

    private fun padBase64(s: String): String {
        val mod = s.length % 4
        return if (mod == 0) s else s + "=".repeat(4 - mod)
    }
}
