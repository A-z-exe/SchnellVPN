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
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * پینگ واقعی سرور:
 * - برای VLESS/VMess/Trojan با TLS/Reality، یک **TLS handshake واقعی** با SNI انجام می‌شود
 *   (نه فقط TCP connect).
 * - اگر handshake ممکن نبود، به TCP برمی‌گردد.
 * - آدرس‌های لوکال (127.0.0.1) پینگ نمی‌شوند و همیشه «Local/Up» هستند.
 * - هر پینگی بالای [MAX_PING_MS] به‌عنوان Timeout در نظر گرفته می‌شود.
 * - سوکت به شبکه‌ی واقعی (Wi‑Fi/دیتا) بایند می‌شود تا وقتی VPN روشن است از تونل رد نشود.
 */
object PingTester {

    const val MAX_PING_MS = 4000

    private fun isIp(s: String): Boolean =
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(s) || s.contains(":")

    private fun isLocalHost(host: String): Boolean =
        host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) ||
            host == "::1" || host == "0.0.0.0"

    private fun sanitize(ms: Int): Int? =
        if (ms in 1..MAX_PING_MS) ms else null

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
        sanitize(((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1))
    } catch (e: Exception) {
        null
    }

    /** TLS handshake واقعی با SNI (برای Reality/TLS). */
    fun handshakePing(
        host: String,
        port: Int,
        serverName: String?,
        timeoutMs: Int = 4000,
        network: Network? = null
    ): Int? = try {
        val addr = InetAddress.getByName(host)
        val start = System.nanoTime()
        val raw = Socket()
        try {
            network?.bindSocket(raw)
            raw.connect(InetSocketAddress(addr, port), timeoutMs)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, serverName ?: host, port, true) as SSLSocket
            ssl.useClientMode = true
            if (!serverName.isNullOrBlank() && !isIp(serverName)) {
                try {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(serverName))
                    ssl.sslParameters = params
                } catch (_: Exception) { }
            }
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            try { ssl.close() } catch (_: Exception) { }
            sanitize(((System.nanoTime() - start) / 1_000_000).toInt())
        } finally {
            try { raw.close() } catch (_: Exception) { }
        }
    } catch (e: Exception) {
        null
    }

    fun pingLink(link: String, network: Network? = null): Int? {
        val (host, port) = XrayConfigBuilder.endpointOf(link) ?: return null
        if (isLocalHost(host)) return 0

        val security = XrayConfigBuilder.securityOf(link)
        return if (security == "tls" || security == "reality") {
            handshakePing(host, port, XrayConfigBuilder.serverNameOf(link), network = network)
                ?: tcpPing(host, port, network = network)
        } else {
            tcpPing(host, port, network = network)
        }
    }

    /** آیا این کانفیگ به یک آدرس لوکال (127.0.0.1/localhost) اشاره می‌کند؟ */
    fun isLocalLink(link: String): Boolean {
        val host = XrayConfigBuilder.endpointOf(link)?.first ?: return false
        return isLocalHost(host)
    }

    /** server id -> latency in ms (null = unreachable / > MAX_PING_MS؛ 0 = local/up) */
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
