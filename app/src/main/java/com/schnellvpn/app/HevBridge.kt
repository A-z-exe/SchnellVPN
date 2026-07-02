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
            Log.d(TAG, "hev-socks5-tunnel loaded OK")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load hev-socks5-tunnel: ${e.message}")
            false
        }
    }

    fun startService(configPath: String, fd: Int): Boolean {
        if (!load()) return false
        return try {
            TProxyStartService(configPath, fd)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "TProxyStartService failed: ${e.message}", e)
            false
        }
    }

    fun stopService() {
        if (!loaded) return
        try { TProxyStopService() }
        catch (e: Throwable) { Log.e(TAG, "TProxyStopService failed: ${e.message}") }
    }

    fun getStats(): LongArray? {
        if (!loaded) return null
        return try { TProxyGetStats() }
        catch (e: Throwable) { null }
    }

    @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic private external fun TProxyStopService()
    @JvmStatic private external fun TProxyGetStats(): LongArray
}
