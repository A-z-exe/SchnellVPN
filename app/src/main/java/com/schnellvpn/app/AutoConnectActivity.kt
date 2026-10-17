package com.schnellvpn.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/** اکتیویتی شفاف که فقط برای استارت‌کردن VPN از سرویس دسترسی استفاده می‌شود. */
class AutoConnectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra(SchnellVpnService.EXTRA_LINK)
        if (!link.isNullOrBlank() && !VpnStatus.isConnected.value && !VpnStatus.isConnecting.value) {
            val i = Intent(this, SchnellVpnService::class.java).apply {
                action = SchnellVpnService.ACTION_CONNECT
                putExtra(SchnellVpnService.EXTRA_LINK, link)
            }
            try {
                ContextCompat.startForegroundService(this, i)
            } catch (_: Exception) {
                // اگر سیستم اجازه نداد، کاربر باید دستی وصل کند
            }
        }
        finish()
    }
}
