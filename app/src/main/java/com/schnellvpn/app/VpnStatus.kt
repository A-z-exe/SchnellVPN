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
    val isConnecting = mutableStateOf(false)
    val txBytes = mutableStateOf(0L)
    val rxBytes = mutableStateOf(0L)
    val connectStartMillis = mutableStateOf(0L)
    val lastError = mutableStateOf<String?>(null)
    /** آیا همین حالا داده واقعاً از تونل رد می‌شود؟ */
    val dataFlowing = mutableStateOf(false)
    /** تأخیر واقعی اندازه‌گیری‌شده از داخل تونل (ms) */
    val tunnelLatencyMs = mutableStateOf(0)
    /** لینک سروری که همین حالا استفاده می‌شود (برای همگام‌سازی UI با سوئیچ خودکار) */
    val currentServerLink = mutableStateOf<String?>(null)

    /** Resets counters/state. Deliberately keeps lastError so the UI can still show why we stopped. */
    fun reset() {
        postToMain {
            isConnected.value = false
            isConnecting.value = false
            txBytes.value = 0L
            rxBytes.value = 0L
            connectStartMillis.value = 0L
            dataFlowing.value = false
            tunnelLatencyMs.value = 0
            currentServerLink.value = null
        }
    }

    val totalMB: Float
        get() = (txBytes.value + rxBytes.value) / (1024f * 1024f)

    fun setConnected(connected: Boolean) = postToMain {
        isConnected.value = connected
        // وقتی اتصال نهایی شد، حالت «در حال اتصال» باید پاک شود (رفع باگ گیرکردن روی «در حال اتصال»)
        if (connected) isConnecting.value = false
    }
    fun setConnecting(connecting: Boolean) = postToMain { isConnecting.value = connecting }
    fun setTxRx(tx: Long, rx: Long) = postToMain {
        txBytes.value = tx
        rxBytes.value = rx
    }
    fun setLastError(err: String?) = postToMain { lastError.value = err }
    fun setConnectStartMillis(ts: Long) = postToMain { connectStartMillis.value = ts }
    fun setDataFlowing(flowing: Boolean) = postToMain { dataFlowing.value = flowing }
    fun setTunnelLatency(ms: Int) = postToMain { tunnelLatencyMs.value = ms }
    fun setCurrentServerLink(link: String?) = postToMain { currentServerLink.value = link }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
