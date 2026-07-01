package com.schnellvpn.app

import androidx.compose.runtime.mutableStateOf

/**
 * وضعیت واقعی اتصال — این Object بین SchnellVpnService و MainActivity مشترکه
 * (چون هر دو توی یک پروسه اجرا می‌شن، نیازی به IPC پیچیده نیست).
 * SchnellVpnService این مقادیر رو هر ثانیه از TProxyGetStats() واقعی پر می‌کنه؛
 * دیگه عدد رندوم/الکی برای حجم مصرفی نداریم.
 */
object VpnStatus {
    val isConnected = mutableStateOf(false)
    val txBytes = mutableStateOf(0L)
    val rxBytes = mutableStateOf(0L)
    val connectStartMillis = mutableStateOf(0L)
    val lastError = mutableStateOf<String?>(null)

    fun reset() {
        isConnected.value = false
        txBytes.value = 0L
        rxBytes.value = 0L
        connectStartMillis.value = 0L
    }

    val totalMB: Float
        get() = (txBytes.value + rxBytes.value) / (1024f * 1024f)
}
