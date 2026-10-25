package com.schnellvpn.app

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * بررسی واقعی عبور دیتا از تونل.
 *
 * «پینگ» فقط می‌گوید پورت سرور از گوشی قابل دسترس است؛ ولی ممکن است کانفیگ
 * اشتباه باشد و دیتا رد نشود. این کلاس از طریق SOCKS محلی Xray یک درخواست
 * واقعی می‌فرستد تا مطمئن شویم تونل کار می‌کند.
 */
object TunnelProbe {

    private val HTTP_ENDPOINTS = listOf(
        "https://1.1.1.1/",                       // بدون نیاز به DNS محلی
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/"
    )

    private val TCP_TARGETS = listOf(
        "1.1.1.1" to 443,
        "8.8.8.8" to 53,
        "9.9.9.9" to 53
    )

    /**
     * اگر تونل کار کند، تأخیر واقعی (میلی‌ثانیه) را برمی‌گرداند؛ در غیر این صورت null.
     * این عدد شامل TCP + TLS + HTTP است، پس از «پینگ خام سرور» بیشتر و صادقانه‌تر است.
     */
    fun probeWithLatency(socksPort: Int, timeoutMs: Int = 4500): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        // ۱) درخواست HTTP واقعی از داخل تونل
        for (urlStr in HTTP_ENDPOINTS) {
            val started = System.currentTimeMillis()
            try {
                val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                val code = conn.responseCode
                try { conn.inputStream?.close() } catch (_: Exception) { }
                conn.disconnect()
                if (code in 200..399) return (System.currentTimeMillis() - started).coerceAtLeast(1L)
            } catch (_: Exception) {
                // سراغ اندپوینت بعدی می‌رویم
            }
        }

        // ۲) اگر HTTP بسته بود، یک TCP CONNECT ساده از داخل تونل امتحان کن
        for ((host, port) in TCP_TARGETS) {
            val started = System.currentTimeMillis()
            try {
                Socket(proxy).use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    if (s.isConnected) return (System.currentTimeMillis() - started).coerceAtLeast(1L)
                }
            } catch (_: Exception) {
                // سراغ هدف بعدی می‌رویم
            }
        }
        return null
    }

    fun probe(socksPort: Int, timeoutMs: Int = 4500): Boolean =
        probeWithLatency(socksPort, timeoutMs) != null
}
