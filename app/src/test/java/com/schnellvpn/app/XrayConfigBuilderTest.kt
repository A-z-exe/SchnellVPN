package com.schnellvpn.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XrayConfigBuilderTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        } catch (e: Exception) {
            fail("expected IllegalArgumentException but got ${e::class.java.simpleName}: ${e.message}")
        }
        fail("expected IllegalArgumentException")
    }

    private fun vnext(o: JSONObject) =
        o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)

    private fun server(o: JSONObject) =
        o.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)

    // ---------------------------------------------------------------- VLESS

    @Test
    fun vlessRealityWithSpacesAndUnicodeInFragment() {
        // java.net.URI throws on the space/unicode fragment — this is the real-world case.
        val link = "vless://$uuid@example.com:8443?type=tcp&security=reality&sni=www.microsoft.com" +
            "&fp=firefox&pbk=PUBKEY123&sid=ab12&flow=xtls-rprx-vision#My Server ✅ ایران"
        val o = XrayConfigBuilder.parseOutbound(link)

        assertEquals("vless", o.getString("protocol"))
        assertEquals("proxy", o.getString("tag"))
        assertEquals("example.com", vnext(o).getString("address"))
        assertEquals(8443, vnext(o).getInt("port"))
        val user = vnext(o).getJSONArray("users").getJSONObject(0)
        assertEquals(uuid, user.getString("id"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        assertEquals("none", user.getString("encryption"))

        val ss = o.getJSONObject("streamSettings")
        assertEquals("reality", ss.getString("security"))
        val r = ss.getJSONObject("realitySettings")
        assertEquals("www.microsoft.com", r.getString("serverName"))
        assertEquals("PUBKEY123", r.getString("publicKey"))
        assertEquals("ab12", r.getString("shortId"))
        assertEquals("firefox", r.getString("fingerprint"))
    }

    @Test
    fun vlessRealityWithoutPublicKeyIsRejected() {
        expectIllegalArgument {
            XrayConfigBuilder.parseOutbound("vless://$uuid@example.com:443?security=reality&sni=a.com#x")
        }
    }

    @Test
    fun vlessWebSocketTls() {
        val link = "vless://$uuid@cdn.example.com:443?type=ws&security=tls&sni=front.example.com" +
            "&host=ws.example.com&path=%2Fchat%3Fed%3D2048&alpn=h2,http/1.1&fp=chrome#ws"
        val ss = XrayConfigBuilder.parseOutbound(link).getJSONObject("streamSettings")

        assertEquals("ws", ss.getString("network"))
        val ws = ss.getJSONObject("wsSettings")
        assertEquals("/chat?ed=2048", ws.getString("path"))
        assertEquals("ws.example.com", ws.getJSONObject("headers").getString("Host"))
        val tls = ss.getJSONObject("tlsSettings")
        assertEquals("front.example.com", tls.getString("serverName"))
        assertFalse(tls.getBoolean("allowInsecure"))
        assertEquals(2, tls.getJSONArray("alpn").length())
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
    }

    @Test
    fun vlessGrpcMultiMode() {
        val link = "vless://$uuid@g.example.com:443?type=grpc&security=tls&serviceName=svc&mode=multi#g"
        val grpc = XrayConfigBuilder.parseOutbound(link)
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("svc", grpc.getString("serviceName"))
        assertTrue(grpc.getBoolean("multiMode"))
    }

    @Test
    fun vlessXhttpAndHttpUpgrade() {
        val x = XrayConfigBuilder.parseOutbound(
            "vless://$uuid@x.example.com:443?type=xhttp&security=tls&path=/x&host=h.example.com&mode=packet-up#x"
        ).getJSONObject("streamSettings")
        assertEquals("xhttp", x.getString("network"))
        assertEquals("packet-up", x.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("/x", x.getJSONObject("xhttpSettings").getString("path"))

        val h = XrayConfigBuilder.parseOutbound(
            "vless://$uuid@x.example.com:443?type=httpupgrade&security=tls&path=/up#u"
        ).getJSONObject("streamSettings")
        assertEquals("/up", h.getJSONObject("httpupgradeSettings").getString("path"))
    }

    @Test
    fun vlessIpv6HostWithBrackets() {
        val o = XrayConfigBuilder.parseOutbound("vless://$uuid@[2001:db8::1]:2053?security=none#v6")
        assertEquals("2001:db8::1", vnext(o).getString("address"))
        assertEquals(2053, vnext(o).getInt("port"))
    }

    @Test
    fun vlessDefaultsToPort443() {
        val o = XrayConfigBuilder.parseOutbound("vless://$uuid@example.com?security=none#d")
        assertEquals(443, vnext(o).getInt("port"))
    }

    @Test
    fun vlessInvalidPortAndMissingUuid() {
        expectIllegalArgument { XrayConfigBuilder.parseOutbound("vless://$uuid@example.com:99999?security=none") }
        expectIllegalArgument { XrayConfigBuilder.parseOutbound("vless://example.com:443?security=none") }
    }

    @Test
    fun vlessUnknownTransportIsRejected() {
        expectIllegalArgument { XrayConfigBuilder.parseOutbound("vless://$uuid@e.com:443?type=carrier-pigeon") }
    }

    // ---------------------------------------------------------------- VMess

    @Test
    fun vmessWebSocketTlsWithNumericPort() {
        val json = """{"v":"2","ps":"vm test","add":"vm.example.com","port":443,"id":"$uuid","aid":"0",
            "scy":"aes-128-gcm","net":"ws","type":"none","host":"ws.vm.com","path":"/vm","tls":"tls","sni":"sni.vm.com"}"""
        val o = XrayConfigBuilder.parseOutbound("vmess://" + b64(json))

        assertEquals("vmess", o.getString("protocol"))
        assertEquals("vm.example.com", vnext(o).getString("address"))
        assertEquals(443, vnext(o).getInt("port"))
        val user = vnext(o).getJSONArray("users").getJSONObject(0)
        assertEquals(uuid, user.getString("id"))
        assertEquals("aes-128-gcm", user.getString("security"))
        val ss = o.getJSONObject("streamSettings")
        assertEquals("tls", ss.getString("security"))
        assertEquals("sni.vm.com", ss.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("/vm", ss.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun vmessGrpcUsesPathAsServiceName() {
        val json = """{"add":"vm.example.com","port":"443","id":"$uuid","net":"grpc","path":"mygrpc","tls":"tls"}"""
        val grpc = XrayConfigBuilder.parseOutbound("vmess://" + b64(json))
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("mygrpc", grpc.getString("serviceName"))
    }

    @Test
    fun vmessBrokenBase64IsRejected() {
        expectIllegalArgument { XrayConfigBuilder.parseOutbound("vmess://%%%not-base64%%%") }
        expectIllegalArgument { XrayConfigBuilder.parseOutbound("vmess://" + b64("not json")) }
    }

    // ---------------------------------------------------------------- Trojan

    @Test
    fun trojanDecodesPasswordAndKeepsPlus() {
        val o = XrayConfigBuilder.parseOutbound(
            "trojan://p%40ss+word@t.example.com:443?sni=t.example.com&type=tcp#t"
        )
        assertEquals("trojan", o.getString("protocol"))
        assertEquals("p@ss+word", server(o).getString("password"))
        assertEquals("t.example.com", server(o).getString("address"))
        assertEquals("tls", o.getJSONObject("streamSettings").getString("security"))
    }

    // ---------------------------------------------------------------- Shadowsocks

    @Test
    fun shadowsocksSip002Base64UserInfo() {
        val link = "ss://" + b64("aes-256-gcm:pa55word") + "@ss.example.com:8388#name"
        val s = server(XrayConfigBuilder.parseOutbound(link))
        assertEquals("aes-256-gcm", s.getString("method"))
        assertEquals("pa55word", s.getString("password"))
        assertEquals("ss.example.com", s.getString("address"))
        assertEquals(8388, s.getInt("port"))
    }

    @Test
    fun shadowsocksLegacyWholeBodyBase64() {
        val link = "ss://" + b64("chacha20-ietf-poly1305:secret@legacy.example.com:1234") + "#legacy"
        val s = server(XrayConfigBuilder.parseOutbound(link))
        assertEquals("chacha20-ietf-poly1305", s.getString("method"))
        assertEquals("secret", s.getString("password"))
        assertEquals("legacy.example.com", s.getString("address"))
        assertEquals(1234, s.getInt("port"))
    }

    @Test
    fun shadowsocksPlainUserInfo() {
        val s = server(XrayConfigBuilder.parseOutbound("ss://aes-128-gcm:pw@1.2.3.4:443#plain"))
        assertEquals("aes-128-gcm", s.getString("method"))
        assertEquals("1.2.3.4", s.getString("address"))
    }

    @Test
    fun shadowsocksPluginIsRejectedInsteadOfSilentlyBroken() {
        expectIllegalArgument {
            XrayConfigBuilder.parseOutbound(
                "ss://" + b64("aes-256-gcm:pw") + "@h.example.com:443/?plugin=v2ray-plugin%3Btls#p"
            )
        }
    }

    // ---------------------------------------------------------------- generic

    @Test
    fun buildConfigHasSocksInboundAndProxyFirst() {
        val cfg = JSONObject(
            XrayConfigBuilder.buildConfig("vless://$uuid@example.com:443?security=none#x", 12345)
        )
        val inbound = cfg.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertEquals(12345, inbound.getInt("port"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))

        val outbounds = cfg.getJSONArray("outbounds")
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("freedom", outbounds.getJSONObject(1).getString("protocol"))
        assertNotNull(cfg.getJSONObject("dns"))
    }

    @Test
    fun jsonOutboundIsUsedAsIsAndRetagged() {
        val ob = """{"protocol":"vless","tag":"old","settings":{"vnext":[{"address":"j.example.com","port":443,
            "users":[{"id":"$uuid","encryption":"none"}]}]}}"""
        val cfg = JSONObject(XrayConfigBuilder.buildConfig(ob))
        assertEquals("proxy", cfg.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test
    fun endpointOfReturnsHostAndPort() {
        assertEquals(
            "example.com" to 8443,
            XrayConfigBuilder.endpointOf("vless://$uuid@example.com:8443?security=none#e")
        )
        assertEquals(
            "ss.example.com" to 8388,
            XrayConfigBuilder.endpointOf("ss://" + b64("aes-256-gcm:pw") + "@ss.example.com:8388")
        )
        assertEquals(
            "j.example.com" to 443,
            XrayConfigBuilder.endpointOf(
                """{"protocol":"vmess","settings":{"vnext":[{"address":"j.example.com","port":443,"users":[]}]}}"""
            )
        )
        assertNull(XrayConfigBuilder.endpointOf("garbage"))
    }

    @Test
    fun emptyAndUnsupportedLinksAreRejected() {
        expectIllegalArgument { XrayConfigBuilder.buildConfig("   ") }
        expectIllegalArgument { XrayConfigBuilder.buildConfig("http://example.com") }
        expectIllegalArgument { XrayConfigBuilder.buildConfig("{ not json") }
    }
}
