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
            Log.d(TAG, "✅ hev-socks5-tunnel loaded OK")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load hev-socks5-tunnel: ${e.message}")
            false
        }
    }

    fun startService(configPath: String, fd: Int): Boolean {
        if (!load()) {
            Log.e(TAG, "❌ Cannot start: library not loaded")
            return false
        }
        return try {
            TProxyStartService(configPath, fd)
            Log.d(TAG, "✅ TProxyStartService called (fd=$fd)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "❌ TProxyStartService failed: ${e.message}", e)
            false
        }
    }

    fun stopService() {
        if (!loaded) return
        try {
            TProxyStopService()
            Log.d(TAG, "✅ TProxyStopService called")
        } catch (e: Throwable) {
            Log.e(TAG, "❌ TProxyStopService failed: ${e.message}")
        }
        loaded = false
    }

    fun getStats(): LongArray? {
        if (!loaded) return null
        return try { TProxyGetStats() }
        catch (e: Throwable) { null }
    }

    fun isLoaded(): Boolean = loaded

    @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic private external fun TProxyStopService()
    @JvmStatic private external fun TProxyGetStats(): LongArray
}
