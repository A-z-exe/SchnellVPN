package com.schnellvpn.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * «تأخیر واقعی کانفیگ» (Real Delay) مثل v2rayNG / Hiddify:
 *
 * برای هر کانفیگ یک outbound واقعی در یک هسته‌ی موقت Xray ساخته می‌شود و از طریق
 * SOCKS محلی همان کانفیگ یک درخواست HTTP واقعی فرستاده و زمان آن اندازه‌گیری می‌شود.
 * بنابراین عددی که نمایش داده می‌شود «زمان واقعی عبور ترافیک از همان کانفیگ» است،
 * نه فقط زمان باز شدن پورت سرور.
 *
 * - همه‌ی کانفیگ‌ها با یک هسته‌ی موقت (چند inbound/outbound) به‌صورت موازی تست می‌شوند.
 * - کانفیگ‌های لوکال (127.0.0.1) تست نمی‌شوند و مقدار 0 می‌گیرند.
 * - اگر ساخت هسته ناموفق باشد، `null` برمی‌گردد تا فراخوان به روش ساده‌تر برگردد.
 */
object RealDelayTester {

    private const val TAG = "SchnellVPN"
    private const val MAX_MS = 4000
    private const val BASE_PORT_CANDIDATES = 20

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    /**
     * server id → latency (ms). 0 = لوکال/بدون نیاز به تست، null = ناموفق.
     * خروجی `null` یعنی هسته‌ی موقت اجرا نشد (نباید نتیجه را «همه خراب» تلقی کرد).
     */
    suspend fun measure(
        context: Context,
        servers: List<VpnServer>,
        concurrency: Int = 8
    ): Map<Int, Int?>? = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext emptyMap()

        val result = HashMap<Int, Int?>()
        val remoteIdx = ArrayList<Int>() // index in servers
        val links = ArrayList<String>()

        servers.forEachIndexed { i, s ->
            if (PingTester.isLocalLink(s.link)) {
                result[s.id] = 0
            } else {
                remoteIdx.add(i)
                links.add(s.link)
            }
        }
        if (links.isEmpty()) return@withContext result

        val basePort = findFreeBasePort(links.size) ?: return@withContext null
        val (config, ports) = try {
            XrayConfigBuilder.buildDelayProbeConfig(links, basePort)
        } catch (e: Exception) {
            Log.w(TAG, "delay config failed: ${e.message}")
            return@withContext null
        }

        val controller = CoreController(callback)
        var started = false
        try {
            try {
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            } catch (e: Exception) {
                Log.w(TAG, "initCoreEnv: ${e.message}")
            }
            controller.startLoop(config, 0)
            started = true

            // صبر تا بالا آمدن SOCKS های موقت
            val usedPorts = ports.filterNotNull()
            var ready = false
            for (attempt in 0 until 40) {
                if (usedPorts.all { canConnect(it) }) { ready = true; break }
                delay(100)
            }
            if (!ready && usedPorts.none { canConnect(it) }) return@withContext null

            val gate = Semaphore(concurrency)
            val measured = coroutineScope {
                links.mapIndexed { idx, _ ->
                    async(Dispatchers.IO) {
                        val port = ports[idx] ?: return@async null
                        gate.withPermit {
                            try {
                                val ms = TunnelProbe.probeWithLatency(port, timeoutMs = MAX_MS)
                                ms?.toInt()?.takeIf { it in 1..MAX_MS }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }

            links.indices.forEach { idx ->
                val server = servers[remoteIdx[idx]]
                result[server.id] = measured[idx]
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Real delay test failed: ${e.message}")
            null
        } finally {
            if (started) {
                try { controller.stopLoop() } catch (e: Exception) {
                    Log.w(TAG, "stop temp core: ${e.message}")
                }
            }
        }
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    } catch (e: Exception) {
        false
    }

    /** یک بازه‌ی پورت آزاد پیدا می‌کند (به تعداد [count] پورت پشت‌سرهم). */
    private fun findFreeBasePort(count: Int): Int? {
        for (candidate in 0 until BASE_PORT_CANDIDATES) {
            val base = 22000 + candidate * 1000
            if (base + count + 8 > 65535) break
            var ok = true
            for (p in base until base + count) {
                try {
                    ServerSocket(p).use { }
                } catch (e: Exception) {
                    ok = false
                    break
                }
            }
            if (ok) return base
        }
        return null
    }
}
