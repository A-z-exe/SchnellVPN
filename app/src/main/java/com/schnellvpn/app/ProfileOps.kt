package com.schnellvpn.app

/**
 * Profile bookkeeping that is pure JVM code (no Android classes) so it can be unit-tested.
 * Two profiles must never share a server list: every server remembers the subscription it
 * came from in [VpnServer.source], and that is what separates merged lists again.
 */
object ProfileOps {

    /**
     * Moves servers to the profile that owns their subscription URL, then dedupes each
     * profile and renumbers it. Repairs lists that older builds merged into both profiles.
     *
     * A server stays put when its source is manual/legacy, when the current profile owns
     * that URL, when no profile owns it, or when several profiles saved the same URL.
     */
    fun repair(profiles: List<ServerProfile>): List<ServerProfile> {
        if (profiles.isEmpty()) return profiles
        val owners = HashMap<String, MutableList<Int>>()
        for (profile in profiles) {
            for (url in profile.subscriptionUrls) {
                owners.getOrPut(url) { mutableListOf() }.add(profile.id)
            }
        }
        val buckets = HashMap<Int, MutableList<VpnServer>>()
        for (profile in profiles) buckets[profile.id] = ArrayList(profile.servers)

        for (profile in profiles) {
            val bucket = buckets[profile.id] ?: continue
            val moved = bucket.filter { server ->
                val source = server.source
                if (source.isBlank() || source == ServerListOps.MANUAL) return@filter false
                if (source in profile.subscriptionUrls) return@filter false
                val ids = owners[source] ?: return@filter false
                ids.size == 1 && ids[0] != profile.id
            }
            for (server in moved) {
                val target = owners[server.source]?.singleOrNull() ?: continue
                bucket.remove(server)
                buckets[target]?.add(server)
            }
        }
        return profiles.map { profile -> tidy(profile.copy(servers = buckets[profile.id] ?: profile.servers)) }
    }

    /** Removes duplicate links inside one profile and numbers it 1..n, keeping the selection by link. */
    fun tidy(profile: ServerProfile): ServerProfile {
        val seen = HashSet<String>()
        val unique = profile.servers.filter { seen.add(ServerListOps.linkKey(it)) }
        val selectedLink = profile.servers.find { it.id == profile.selectedServerId }?.link
        val (numbered, selected) = ServerListOps.renumber(unique, selectedLink)
        return profile.copy(servers = numbered, selectedServerId = selected)
    }
}
