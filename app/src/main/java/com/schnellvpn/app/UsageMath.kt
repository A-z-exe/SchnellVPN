package com.schnellvpn.app

import java.util.Locale

data class UidUsage(val uid: Int, val totalBytes: Long, val bytesPerSec: Long)

/** Pure helpers for the "top apps" card (no Android classes → unit-testable). */
object UsageMath {

    /** First uid that belongs to an installed app (below that: root, system, media server, ...). */
    const val FIRST_APP_UID = 10000

    /**
     * Ranks uids by total bytes (descending).
     * Skips system uids, [excludeUid] (this app) and uids without traffic.
     * The speed is the growth since [previous] divided by [elapsedMs]; 0 when unknown or when the counter went down.
     */
    fun rank(
        current: Map<Int, Long>,
        previous: Map<Int, Long>?,
        elapsedMs: Long,
        excludeUid: Int
    ): List<UidUsage> {
        val out = ArrayList<UidUsage>()
        for ((uid, total) in current) {
            if (uid < 0 || uid % 100000 < FIRST_APP_UID || uid == excludeUid || total <= 0L) continue
            val prev = previous?.get(uid)
            val rate = if (prev != null && elapsedMs > 0 && total >= prev) (total - prev) * 1000L / elapsedMs else 0L
            out.add(UidUsage(uid, total, rate))
        }
        return out.sortedByDescending { it.totalBytes }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
