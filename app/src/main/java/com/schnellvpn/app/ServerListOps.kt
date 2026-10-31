package com.schnellvpn.app

/**
 * Pure list logic (no Android classes) so it can be unit-tested on a plain JVM.
 */
object ServerListOps {

    /** `source` value for servers added from a QR code / single link. */
    const val MANUAL = "manual"

    /** Appends servers whose link isn't in [existing] yet; new ids continue after the current max. */
    fun addUnique(existing: List<VpnServer>, incoming: List<VpnServer>, source: String): List<VpnServer> {
        val seen = existing.map { it.link }.toHashSet()
        var nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val out = ArrayList<VpnServer>(existing)
        for (s in incoming) {
            if (!seen.add(s.link)) continue
            out.add(s.copy(id = nextId++, source = source))
        }
        return out
    }

    /**
     * Result of re-downloading the saved subscriptions.
     * [fetched]: subscription URL -> its fresh servers, or null if that download failed.
     *
     *  - freshly downloaded servers replace the old servers of the same subscription
     *  - a failed subscription keeps its old servers (a network hiccup never wipes the list)
     *  - manually added servers (QR / single link) are always kept
     *  - servers saved by older versions (source == "") came from a subscription: they are replaced
     *    as soon as at least one download succeeds
     *  - duplicates (same link) are removed, fresh entries win
     */
    fun mergeRefresh(existing: List<VpnServer>, fetched: Map<String, List<VpnServer>?>): List<VpnServer> {
        val anySuccess = fetched.values.any { it != null }

        val kept = existing.filter { s ->
            when {
                s.source == MANUAL -> true
                s.source.isEmpty() -> !anySuccess
                fetched.containsKey(s.source) -> fetched[s.source] == null
                else -> true // belongs to a subscription that is no longer saved
            }
        }

        val seen = HashSet<String>()
        val out = ArrayList<VpnServer>()
        for (list in fetched.values) {
            if (list == null) continue
            for (s in list) if (seen.add(dupKey(s))) out.add(s)
        }
        for (s in kept) if (seen.add(dupKey(s))) out.add(s)
        return out
    }

    /** کلید حذف تکراری: کانفیگ‌های لوکال با نام متفاوت باید جدا بمانند. */
    private fun dupKey(s: VpnServer): String =
        if (isLocalLink(s.link)) s.link + "|" + s.name else s.link

    /** آیا این کانفیگ به آدرس لوکال اشاره می‌کند؟ (همیشه بالای لیست و بدون پینگ) */
    fun isLocalLink(link: String): Boolean = try {
        val h = XrayConfigBuilder.endpointOf(link)?.first
        h == "127.0.0.1" || h.equals("localhost", ignoreCase = true) || h == "::1" || h == "0.0.0.0"
    } catch (e: Exception) {
        false
    }

    /** Lowest ping first; local (127.0.0.1) always first; unreachable (null) last; equal pings keep order. */
    fun sortByPing(list: List<VpnServer>): List<VpnServer> {
        val localFlags = list.map { isLocalLink(it.link) }
        return list.indices
            .sortedWith(
                compareBy<Int> { !localFlags[it] && list[it].pingMs == null }
                    .thenBy { if (localFlags[it]) 0 else list[it].pingMs ?: Int.MAX_VALUE }
            )
            .map { list[it] }
    }

    /** Re-assigns ids 1..n in list order. Returns the list and the new id of [selectedLink] (or the first server). */
    fun renumber(list: List<VpnServer>, selectedLink: String?): Pair<List<VpnServer>, Int> {
        val numbered = list.mapIndexed { i, s -> s.copy(id = i + 1) }
        val selected = numbered.firstOrNull { it.link == selectedLink }?.id
            ?: numbered.firstOrNull()?.id
            ?: -1
        return numbered to selected
    }

    /** Writes ping results (keyed by the ids in [numbered]), sorts by ping and renumbers. */
    fun applyPings(
        numbered: List<VpnServer>,
        pings: Map<Int, Int?>,
        selectedLink: String?
    ): Pair<List<VpnServer>, Int> {
        val withPing = numbered.map { it.copy(pingMs = pings[it.id]) }
        return renumber(sortByPing(withPing), selectedLink)
    }
}
