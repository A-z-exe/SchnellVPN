package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOpsTest {

    private val urlA = "https://a.example/sub"
    private val urlB = "https://b.example/sub"

    private fun srv(id: Int, link: String, source: String = "") =
        VpnServer(id, "🌐", "n$id", "VLESS", link, null, source)

    private fun profile(id: Int, urls: List<String>, servers: List<VpnServer>) =
        ServerProfile(id, "p$id", servers, servers.firstOrNull()?.id ?: -1, urls)

    @Test
    fun mergedListsAreSeparatedBySubscriptionSource() {
        val first = profile(
            1, listOf(urlA),
            listOf(srv(1, "l1", urlA), srv(2, "l2", urlB), srv(3, "manual", ServerListOps.MANUAL))
        )
        val second = profile(2, listOf(urlB), listOf(srv(1, "l2", urlB), srv(2, "l1", urlA)))

        val out = ProfileOps.repair(listOf(first, second))

        assertEquals(listOf("l1", "manual"), out[0].servers.map { it.link })
        assertEquals(listOf("l2"), out[1].servers.map { it.link })
        assertEquals(listOf(1, 2), out[0].servers.map { it.id })
        assertEquals(1, out[1].servers.map { it.id }.single())
        assertEquals(1, out[0].selectedServerId)
    }

    @Test
    fun duplicateLinksAreRemovedWithinAProfile() {
        val p = profile(
            1, listOf(urlA),
            listOf(srv(1, "same", urlA), srv(2, "same", urlA), srv(3, "other", urlA))
        )
        val out = ProfileOps.repair(listOf(p)).single()
        assertEquals(listOf("same", "other"), out.servers.map { it.link })
        assertEquals(listOf(1, 2), out.servers.map { it.id })
    }

    @Test
    fun manualAndLegacyServersStayWhereTheyAre() {
        val p = profile(
            1, listOf(urlA),
            listOf(srv(1, "m", ServerListOps.MANUAL), srv(2, "old", ""))
        )
        assertEquals(
            listOf("m", "old"),
            ProfileOps.repair(listOf(p)).single().servers.map { it.link }
        )
    }

    @Test
    fun unknownSourceStaysWhereItIs() {
        val p = profile(1, listOf(urlA), listOf(srv(1, "x", urlB)))
        assertEquals(listOf("x"), ProfileOps.repair(listOf(p)).single().servers.map { it.link })
    }

    @Test
    fun aUrlSavedInTwoProfilesDoesNotMoveServers() {
        val first = profile(1, listOf(urlA), listOf(srv(1, "l1", urlA)))
        val second = profile(2, listOf(urlA), listOf(srv(1, "l1", urlA)))
        val out = ProfileOps.repair(listOf(first, second))
        assertEquals(1, out[0].servers.size)
        assertEquals(1, out[1].servers.size)
    }

    @Test
    fun selectionFollowsTheServerAfterRenumbering() {
        val p = profile(1, listOf(urlA), listOf(srv(5, "a", urlA), srv(9, "b", urlA), srv(7, "c", urlA)))
            .copy(selectedServerId = 9)
        val out = ProfileOps.tidy(p)
        assertEquals(2, out.selectedServerId)
        assertEquals("b", out.servers[1].link)
    }

    @Test
    fun tidyPinsInfoServersToTheTop() {
        val info = "vless://11111111-2222-3333-4444-555555555555@info.example.com:443?type=tcp&security=tls#info"
        val p = profile(
            1, listOf(urlA),
            listOf(srv(1, "normal", urlA), srv(2, info, urlA), srv(3, "other", urlA))
        )
        val out = ProfileOps.tidy(p)
        assertEquals(listOf(info, "normal", "other"), out.servers.map { it.link })
        assertEquals(listOf(1, 2, 3), out.servers.map { it.id })
    }
}
