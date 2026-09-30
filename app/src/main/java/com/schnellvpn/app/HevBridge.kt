package com.schnellvpn.app

import android.util.Log
import java.io.File

/** Safe wrapper around the native hev-socks5-tunnel (TUN fd -> local SOCKS5). */
object HevBridge {

    private const val TAG = "HevBridge"

    @Volatile
    private var loaded = false

    /** Loads the native library once; never throws. */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            Class.forName("hev.htproxy.TProxyService") // runs System.loadLibrary + RegisterNatives
            loaded = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "native load failed: ${t.message}", t)
            false
        }
    }

    fun startService(configPath: String, tunFd: Int): Boolean {
        if (!load()) return false
        return try {
            hev.htproxy.TProxyService.TProxyStartService(configPath, tunFd)
        } catch (t: Throwable) {
            Log.e(TAG, "TProxyStartService failed: ${t.message}", t)
            false
        }
    }

    fun stopService() {
        if (!loaded) return
        try {
            hev.htproxy.TProxyService.TProxyStopService()
        } catch (t: Throwable) {
            Log.w(TAG, "stopService: ${t.message}")
        }
    }

    fun isRunning(): Boolean = loaded && try {
        hev.htproxy.TProxyService.TProxyIsRunning()
    } catch (t: Throwable) {
        false
    }

    /** [txPackets, txBytes, rxPackets, rxBytes] or null. */
    fun getStats(): LongArray? {
        if (!loaded) return null
        return try {
            hev.htproxy.TProxyService.TProxyGetStats()
        } catch (t: Throwable) {
            Log.e(TAG, "getStats: ${t.message}")
            null
        }
    }

    /** Always rewrites the config so it can never go stale. */
    fun writeConfig(dir: File, socksPort: Int, mtu: Int, ipv4: String, ipv6: String): File {
        val f = File(dir, "hev_tunnel.yml")
        f.writeText(
            """
            tunnel:
              mtu: $mtu
              ipv4: $ipv4
              ipv6: '$ipv6'
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent() + "\n"
        )
        return f
    }
}
