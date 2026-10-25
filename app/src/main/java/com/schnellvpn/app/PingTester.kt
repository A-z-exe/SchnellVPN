package com.schnellvpn.app

import android.net.Network
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

    /**
     * اگر [network] داده شود، سوکت به شبکه‌ی واقعی (Wi‑Fi/دیتا) بایند می‌شود
     * تا وقتی VPN روشن است، پینگ از داخل تونل رد نشود و عدد فیک ندهد.
     */
    fun tcpPing(
        host: String,
        port: Int,
        timeoutMs: Int = 3000,
        network: Network? = null
    ): Int? = try {
        val addr = InetAddress.getByName(host)
        val start = System.nanoTime()
        val socket = Socket()
        try {
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(addr, port), timeoutMs)
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
        ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
    } catch (e: Exception) {
        null
    }

    fun pingLink(link: String, network: Network? = null): Int? {
        val (host, port) = XrayConfigBuilder.endpointOf(link) ?: return null
        return tcpPing(host, port, network = network)
    }

    /** server id -> latency in ms (null = unreachable) */
    suspend fun pingAll(
        servers: List<VpnServer>,
        concurrency: Int = 8,
        network: Network? = null
    ): Map<Int, Int?> = coroutineScope {
        val gate = Semaphore(concurrency)
        servers.map { s ->
            async(Dispatchers.IO) { gate.withPermit { s.id to pingLink(s.link, network) } }
        }.awaitAll().toMap()
    }
}
