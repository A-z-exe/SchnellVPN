package com.schnellvpn.app

import android.util.Log

object HevBridge {
    private const val TAG = "HevBridge"
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true

        return try {
            System.loadLibrary("hev-socks5-tunnel")
            loaded = true
            Log.d(TAG, "✅ hev-socks5-tunnel loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ load failed: ${e.message}")
            false
        }
    }

    fun startService(configPath: String, fd: Int): Boolean {
        if (!load()) return false

        return try {
            hev.htproxy.TProxyService.TProxyStartService(configPath, fd)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startService failed: ${e.message}")
            false
        }
    }

    fun stopService() {
        if (!loaded) return

        try {
            hev.htproxy.TProxyService.TProxyStopService()
        } catch (e: Throwable) {
            Log.e(TAG, "stopService: ${e.message}")
        }
    }

    fun getStats(): LongArray? {
        if (!loaded) return null

        return try {
            // TProxyGetStats معمولاً IntArray برمی‌گرداند
            // اگر نسخه کتابخانه LongArray برگرداند، این تبدیل باید تغییر کند.
            val raw = hev.htproxy.TProxyService.TProxyGetStats() as IntArray
            LongArray(raw.size) { raw[it].toLong() }
        } catch (e: Throwable) {
            Log.e(TAG, "getStats failed: ${e.message}")
            null
        }
    }
}
