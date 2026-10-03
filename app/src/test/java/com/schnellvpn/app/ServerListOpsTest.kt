package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListOpsTest {

    private fun srv(id: Int, link: String, ping: Int? = null, source: String = "") =
        VpnServer(id, "🌐", "n$id", "VLESS", link, ping, source)

    private val urlA = "https://a.example/sub"
    private val urlB = "https://b.example/sub"

    @Test
    fun sortByPingPutsLowestFirstAndUnreachableLast() {
        val sorted = ServerListOps.sortByPing(
            listOf(srv(1, "l1", null), srv(2, "l2", 300), srv(3, "l3", 40), srv(4, "l4", null), srv(5, "l5", 120))
        )
        assertEquals(listOf("l3", "l5", "l2", "l1", "l4"), sorted.map { it.link })
    }

    @Test
    fun sortByPingIsStableForEqualPings() {
        val sorted = ServerListOps.sortByPing(listOf(srv(1, "a", 50), srv(2, "b", 50), srv(3, "c", 50)))
        assertEquals(listOf("a", "b", "c"), sorted.map { it.link })
    }

    @Test
    fun applyPingsSortsRenumbersAndKeepsSelectionByLink() {
        val numbered = listOf(srv(1, "slow"), srv(2, "fast"), srv(3, "dead"), srv(4, "mid"))
        val pings: Map<Int, Int?> = mapOf(1 to 400, 2 to 30, 3 to null, 4 to 90)

        val (list, selected) = ServerListOps.applyPings(numbered, pings, "mid")

        assertEquals(listOf("fast", "mid", "slow", "dead"), list.map { it.link })
        assertEquals(listOf(1, 2, 3, 4), list.map { it.id })
        assertEquals(listOf<Int?>(30, 90, 400, null), list.map { it.pingMs })
        assertEquals(2, selected) // "mid" moved to position 2
    }

    @Test
    fun selectionFallsBackToFirstWhenLinkIsGone() {
        val (list, selected) = ServerListOps.renumber(listOf(srv(7, "x"), srv(9, "y")), "missing")
        assertEquals(listOf(1, 2), list.map { it.id })
        assertEquals(1, selected)
        assertEquals(-1, ServerListOps.renumber(emptyList(), null).second)
    }

    @Test
    fun addUniqueSkipsKnownLinksAndContinuesIds() {
        val existing = listOf(srv(1, "a", source = urlA), srv(5, "b", source = urlA))
        val merged = ServerListOps.addUnique(existing, listOf(srv(1, "b"), srv(2, "c"), srv(3, "c"), srv(4, "d")), ServerListOps.MANUAL)

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.link })
        assertEquals(listOf(1, 5, 6, 7), merged.map { it.id })
        assertEquals(listOf(urlA, urlA, ServerListOps.MANUAL, ServerListOps.MANUAL), merged.map { it.source })
    }

    @Test
    fun refreshReplacesOldServersOfTheSameSubscriptionAndKeepsManualOnes() {
        val existing = listOf(
            srv(1, "old1", source = urlA),
            srv(2, "keep", source = urlA),
            srv(3, "qr", source = ServerListOps.MANUAL)
        )
        val fetched: Map<String, List<VpnServer>?> = mapOf(urlA to listOf(srv(1, "keep", source = urlA), srv(2, "new", source = urlA)))

        val merged = ServerListOps.mergeRefresh(existing, fetched)

        assertEquals(listOf("keep", "new", "qr"), merged.map { it.link }) // "old1" disappeared from the subscription
    }

    @Test
    fun failedDownloadKeepsItsOldServers() {
        val existing = listOf(srv(1, "a1", source = urlA), srv(2, "b1", source = urlB))
        val fetched: Map<String, List<VpnServer>?> = mapOf(urlA to null, urlB to listOf(srv(1, "b2", source = urlB)))

        val merged = ServerListOps.mergeRefresh(existing, fetched)

        assertEquals(listOf("b2", "a1"), merged.map { it.link })
    }

    @Test
    fun legacyServersAreReplacedOnlyWhenSomeDownloadSucceeds() {
        val legacy = listOf(srv(1, "old", source = ""))

        val ok = ServerListOps.mergeRefresh(legacy, mapOf(urlA to listOf(srv(1, "new", source = urlA))))
        assertEquals(listOf("new"), ok.map { it.link })

        val allFailed = ServerListOps.mergeRefresh(legacy, mapOf<String, List<VpnServer>?>(urlA to null))
        assertEquals(listOf("old"), allFailed.map { it.link })
    }

    @Test
    fun refreshRemovesDuplicateLinks() {
        val existing = listOf(srv(1, "dup", source = ServerListOps.MANUAL))
        val fetched: Map<String, List<VpnServer>?> = mapOf(urlA to listOf(srv(1, "dup", source = urlA), srv(2, "x", source = urlA)))

        val merged = ServerListOps.mergeRefresh(existing, fetched)

        assertEquals(listOf("dup", "x"), merged.map { it.link })
        assertEquals(urlA, merged[0].source) // fresh entry wins
    }
}
