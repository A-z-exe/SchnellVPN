package com.schnellvpn.app

import android.util.Log
import java.io.File

/**
 * Bridge to the native hev-socks5-tunnel library.
 *
 * The native lib registers its JNI methods against hev.htproxy.TProxyService,
 * so that class MUST declare every native method the C code expects —
 * including TProxyIsRunning (its absence caused the SIGABRT on load).
 */
object HevBridge {

    private const val TAG = "HevBridge"

    @Volatile
    private var loaded = false

    /** Must live in package hev.htproxy with exactly these signatures. */
    class TProxyService {
        companion object {
            init {
                System.loadLibrary("hev-socks5-tunnel")
            }

            @JvmStatic
            external fun TProxyStartService(configPath: String, fd: Int)

            @JvmStatic
            external fun TProxyStopService()

            @JvmStatic
            external fun TProxyIsRunning(): Boolean

            @JvmStatic
            external fun TProxyGetStats(): IntArray  // [txBytes, rxBytes]
        }
    }

    /** Load the native library once; safe to call multiple times. */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            // Force class init -> System.loadLibrary -> JNI RegisterNatives
            Class.forName("hev.htproxy.TProxyService")
            loaded = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load failed: ${t.message}", t)
            false
        }
    }

    fun startService(configPath: String, tunFd: Int): Boolean {
        if (!load()) return false
        return try {
            TProxyService.TProxyStartService(configPath, tunFd)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "TProxyStartService failed: ${t.message}", t)
            false
        }
    }

    fun stopService() {
        try {
            if (isRunning()) TProxyService.TProxyStopService()
        } catch (t: Throwable) {
            Log.w(TAG, "stopService: ${t.message}")
        }
    }

    fun isRunning(): Boolean = try {
        TProxyService.TProxyIsRunning()
    } catch (t: Throwable) {
        false
    }

    /** Returns [txBytes, rxBytes] or null. Native returns IntArray (JNI sig [I]). */
    fun getStats(): LongArray? {
        if (!loaded) return null
        return try {
            val raw = TProxyService.TProxyGetStats()
            LongArray(raw.size) { raw[it].toLong() }
        } catch (t: Throwable) {
            Log.e(TAG, "getStats: ${t.message}")
            null
        }
    }

    /** Writes the hev-socks5-tunnel YAML config. */
    fun writeConfig(dir: File, socksPort: Int): File {
        val f = File(dir, "hev.conf")
        f.writeText(
            """
            tunnel:
              name: tun
              mtu: 8500
              multi-queue: true
            socks5:
              address: '127.0.0.1:$socksPort'
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent() + "\n"
        )
        return f
    }
}
