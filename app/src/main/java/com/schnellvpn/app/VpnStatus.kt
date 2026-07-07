package com.schnellvpn.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/**
 * وضعیت اشتراکی VPN بین Service و UI
 * تمامی تغییر‌های خارجی ترجیحاً از طریق متدهای ست کننده انجام شود تا از thread-safe بودن و
 * ارسال به Main thread اطمینان حاصل شود.
 */
object VpnStatus {
    private val mainHandler = Handler(Looper.getMainLooper())

    val isConnected = mutableStateOf(false)
    val txBytes = mutableStateOf(0L)
    val rxBytes = mutableStateOf(0L)
    val connectStartMillis = mutableStateOf(0L)
    val lastError = mutableStateOf<String?>(null)

    fun reset() {
        postToMain {
            isConnected.value = false
            txBytes.value = 0L
            rxBytes.value = 0L
            connectStartMillis.value = 0L
            lastError.value = null
        }
    }

    val totalMB: Float
        get() = (txBytes.value + rxBytes.value) / (1024f * 1024f)

    // Helper setters that ensure updates happen on Main thread
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
