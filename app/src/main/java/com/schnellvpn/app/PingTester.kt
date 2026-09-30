package com.schnellvpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Real TCP-connect latency to each server's host:port (DNS time excluded). */
object PingTester {

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Int? = try {
        val addr = InetAddress.getByName(host)
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
        ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
    } catch (e: Exception) {
        null
    }

    fun pingLink(link: String): Int? {
        val (host, port) = XrayConfigBuilder.endpointOf(link) ?: return null
        return tcpPing(host, port)
    }

    /** server id -> latency in ms (null = unreachable) */
    suspend fun pingAll(servers: List<VpnServer>, concurrency: Int = 8): Map<Int, Int?> = coroutineScope {
        val gate = Semaphore(concurrency)
        servers.map { s ->
            async(Dispatchers.IO) { gate.withPermit { s.id to pingLink(s.link) } }
        }.awaitAll().toMap()
    }
}
