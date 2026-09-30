package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParseTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private val vlessReality =
        "vless://$uuid@a.example.com:443?type=tcp&security=reality&sni=www.microsoft.com&pbk=PBK&sid=01#Germany 🇩🇪"
    private val vlessWs = "vless://$uuid@b.example.com:443?type=ws&security=tls&path=/w#WS One"
    private val trojan = "trojan://secret@c.example.com:443#Trojan%20Node"
    private val vmessJson =
        """{"ps":"VMess Node","add":"d.example.com","port":"443","id":"$uuid","net":"tcp","tls":"tls"}"""

    @Test
    fun plainLinkListSkipsGarbageCommentsAndDuplicates() {
        val body = listOf(
            "# profile-title: test", vlessReality, "", "some random text", vlessWs, vlessReality, trojan
        ).joinToString("\n")
        val servers = SubscriptionFetcher.parseContent(body)

        assertEquals(3, servers.size)
        assertEquals("Germany 🇩🇪", servers[0].name)
        assertEquals("VLESS · Reality", servers[0].protocolLabel)
        assertEquals("WS One", servers[1].name)
        assertEquals("VLESS · WS", servers[1].protocolLabel)
        assertEquals("Trojan Node", servers[2].name)
        assertEquals(listOf(1, 2, 3), servers.map { it.id })
    }

    @Test
    fun base64Subscription() {
        val body = b64(listOf(vlessReality, vlessWs, trojan).joinToString("\n"))
        val servers = SubscriptionFetcher.parseContent(body)
        assertEquals(3, servers.size)
    }

    @Test
    fun urlSafeBase64WithoutPaddingSubscription() {
        val raw = listOf(vlessReality, vlessWs).joinToString("\r\n")
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        assertEquals(2, SubscriptionFetcher.parseContent(body).size)
    }

    @Test
    fun vmessNameComesFromPsField() {
        val servers = SubscriptionFetcher.parseContent("vmess://" + b64(vmessJson))
        assertEquals(1, servers.size)
        assertEquals("VMess Node", servers[0].name)
        assertEquals("VMess", servers[0].protocolLabel)
    }

    @Test
    fun brokenLinksAreDroppedSoTheyCannotCrashConnect() {
        val body = listOf(
            "vless://$uuid@a.example.com:99999?security=none#badport",
            "vless://a.example.com:443#nouuid",
            "vmess://!!!!",
            "ss://garbage",
            vlessWs
        ).joinToString("\n")
        val servers = SubscriptionFetcher.parseContent(body)
        assertEquals(1, servers.size)
        assertEquals("WS One", servers[0].name)
    }

    @Test
    fun nameFallsBackToHostWhenNoRemark() {
        val servers = SubscriptionFetcher.parseContent("vless://$uuid@nohash.example.com:443?security=none")
        assertEquals("nohash.example.com", servers[0].name)
    }

    @Test
    fun v2rayNJsonArray() {
        val json = """[
          {"remarks":"JSON One","outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"j1.example.com","port":443,
              "users":[{"id":"$uuid","encryption":"none"}]}]},
             "streamSettings":{"network":"grpc","security":"reality"}},
            {"tag":"direct","protocol":"freedom"}]},
          {"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"j2.example.com",
            "port":443,"password":"pw"}]}}]},
          {"remarks":"no outbounds"}
        ]"""
        val servers = SubscriptionFetcher.parseContent(json)

        assertEquals(2, servers.size)
        assertEquals("JSON One", servers[0].name)
        assertEquals("VLESS · Reality", servers[0].protocolLabel)
        assertEquals("j2.example.com", servers[1].name)
        // the stored link must itself be usable by the config builder
        assertTrue(XrayConfigBuilder.buildConfig(servers[0].link).contains("j1.example.com"))
    }

    @Test
    fun singleJsonOutboundObject() {
        val json = """{"protocol":"shadowsocks","settings":{"servers":[{"address":"s.example.com","port":8388,
            "method":"aes-256-gcm","password":"pw"}]}}"""
        val servers = SubscriptionFetcher.parseContent(json)
        assertEquals(1, servers.size)
        assertEquals("Shadowsocks", servers[0].protocolLabel)
    }

    @Test
    fun emptyAndUnparseableBodiesGiveEmptyList() {
        assertTrue(SubscriptionFetcher.parseContent("").isEmpty())
        assertTrue(SubscriptionFetcher.parseContent("   \n  ").isEmpty())
        assertTrue(SubscriptionFetcher.parseContent("<html>Not found</html>").isEmpty())
        assertTrue(SubscriptionFetcher.parseContent("[ not json").isEmpty())
    }

    @Test
    fun byteOrderMarkIsTolerated() {
        assertEquals(1, SubscriptionFetcher.parseContent("\uFEFF" + vlessWs).size)
    }
}
