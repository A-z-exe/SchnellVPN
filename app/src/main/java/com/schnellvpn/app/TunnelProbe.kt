package com.schnellvpn.app

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * بررسی واقعی عبور دیتا از تونل + اندازه‌گیری تأخیر.
 *
 * «پینگ» فقط می‌گوید پورت سرور از گوشی قابل دسترس است؛ ولی ممکن است کانفیگ
 * اشتباه باشد و دیتا رد نشود. این کلاس از طریق SOCKS محلی Xray یک درخواست
 * واقعی می‌فرستد تا هم سالم بودن مسیر و هم تأخیر واقعی آن اندازه‌گیری شود.
 *
 * نکته‌ی سرعت: [timeoutMs] یک «بودجه‌ی کل» است، نه زمان هر تلاش. هدف‌ها هم
 * سبک انتخاب شده‌اند (generate_204) تا یک رفت‌وبرگشت سریع اندازه‌گیری شود.
 * قبلاً اولین هدف 1.1.1.1 بود که در بسیاری از شبکه‌ها کند/مسدود است و باعث
 * می‌شد زمان اتصال و پینگ الکی چند ثانیه شود.
 */
object TunnelProbe {

    private val HTTP_ENDPOINTS = listOf(
        "https://www.gstatic.com/generate_204",   // هدف استاندارد تست تأخیر
        "https://cp.cloudflare.com/generate_204",
        "https://1.1.1.1/"                        // بدون نیاز به DNS
    )

    private val TCP_TARGETS = listOf(
        "1.1.1.1" to 443,
        "8.8.8.8" to 53,
        "9.9.9.9" to 53
    )

    /**
     * اگر تونل کار کند، تأخیر واقعی (میلی‌ثانیه) را برمی‌گرداند؛ در غیر این صورت null.
     * کل زمان صرف‌شده هرگز از [timeoutMs] بیشتر نمی‌شود.
     */
    fun probeWithLatency(socksPort: Int, timeoutMs: Int = 3000): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val deadline = System.currentTimeMillis() + timeoutMs

        // ۱) یک درخواست HTTP واقعی از داخل تونل (سریع‌ترین هدف اول)
        for (urlStr in HTTP_ENDPOINTS) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left < 350) break
            val perTry = minOf(left, 1500)
            val started = System.currentTimeMillis()
            try {
                val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = perTry
                conn.readTimeout = perTry
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                val code = conn.responseCode
                try { conn.inputStream?.close() } catch (_: Exception) { }
                conn.disconnect()
                if (code in 200..399) return (System.currentTimeMillis() - started).coerceAtLeast(1L)
            } catch (_: Exception) {
                // سراغ اندپوینت بعدی می‌رویم
            }
        }

        // ۲) اگر HTTP بسته بود، یک TCP CONNECT ساده از داخل تونل
        for ((host, port) in TCP_TARGETS) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left < 250) break
            val started = System.currentTimeMillis()
            try {
                Socket(proxy).use { s ->
                    s.connect(InetSocketAddress(host, port), minOf(left, 1500))
                    if (s.isConnected) return (System.currentTimeMillis() - started).coerceAtLeast(1L)
                }
            } catch (_: Exception) {
                // سراغ هدف بعدی می‌رویم
            }
        }
        return null
    }

    fun probe(socksPort: Int, timeoutMs: Int = 3000): Boolean =
        probeWithLatency(socksPort, timeoutMs) != null
}
