package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMathTest {

    @Test
    fun ranksByTotalDescendingAndSkipsSystemOwnAndEmptyUids() {
        val current = mapOf(
            0 to 9_000L,          // root
            1000 to 8_000L,       // system
            10050 to 500L,
            10051 to 2_000L,      // this app -> excluded
            10052 to 3_000L,
            10053 to 0L,          // no traffic
            -4 to 7_000L          // UID_REMOVED
        )
        val ranked = UsageMath.rank(current, null, 0L, excludeUid = 10051)
        assertEquals(listOf(10052, 10050), ranked.map { it.uid })
    }

    @Test
    fun multiUserProfilesAreStillRecognisedAsApps() {
        val ranked = UsageMath.rank(mapOf(1010123 to 100L, 1001000 to 100L), null, 0L, excludeUid = 10001)
        assertEquals(listOf(1010123), ranked.map { it.uid })
    }

    @Test
    fun speedIsGrowthPerSecond() {
        val prev = mapOf(10060 to 1_000_000L)
        val cur = mapOf(10060 to 1_500_000L)
        val ranked = UsageMath.rank(cur, prev, 4_000L, excludeUid = 1)
        assertEquals(125_000L, ranked[0].bytesPerSec)
        assertEquals(1_500_000L, ranked[0].totalBytes)
    }

    @Test
    fun speedIsZeroWhenUnknownOrCounterWentBack() {
        val cur = mapOf(10060 to 500L)
        assertEquals(0L, UsageMath.rank(cur, null, 4_000L, 1)[0].bytesPerSec)          // first sample
        assertEquals(0L, UsageMath.rank(cur, mapOf(10060 to 900L), 4_000L, 1)[0].bytesPerSec) // midnight reset
        assertEquals(0L, UsageMath.rank(cur, mapOf(10060 to 100L), 0L, 1)[0].bytesPerSec)     // no time elapsed
        assertEquals(0L, UsageMath.rank(cur, emptyMap(), 4_000L, 1)[0].bytesPerSec)           // app not seen before
    }

    @Test
    fun formatsBytesWithLatinDigits() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1023L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("5.0 MB", formatBytes(5L * 1024 * 1024))
        assertEquals("1.2 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.0 TB", formatBytes(2L * 1024 * 1024 * 1024 * 1024))
    }
}
