package com.schnellvpn.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/**
 * Shared VPN state between Service and UI.
 * Always mutate through the setters so updates are posted to the main thread.
 */
object VpnStatus {
    private val mainHandler = Handler(Looper.getMainLooper())

    val isConnected = mutableStateOf(false)
    val txBytes = mutableStateOf(0L)
    val rxBytes = mutableStateOf(0L)
    val connectStartMillis = mutableStateOf(0L)
    val lastError = mutableStateOf<String?>(null)

    /** Resets counters/state. Deliberately keeps lastError so the UI can still show why we stopped. */
    fun reset() {
        postToMain {
            isConnected.value = false
            txBytes.value = 0L
            rxBytes.value = 0L
            connectStartMillis.value = 0L
        }
    }

    val totalMB: Float
        get() = (txBytes.value + rxBytes.value) / (1024f * 1024f)

    fun setConnected(connected: Boolean) = postToMain { isConnected.value = connected }
    fun setTxRx(tx: Long, rx: Long) = postToMain {
        txBytes.value = tx
        rxBytes.value = rx
    }
    fun setLastError(err: String?) = postToMain { lastError.value = err }
    fun setConnectStartMillis(ts: Long) = postToMain { connectStartMillis.value = ts }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
