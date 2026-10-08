package com.schnellvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * با روشن شدن گوشی، اگر «اتصال خودکار» فعال باشد و حداقل یک سرور ذخیره شده باشد، VPN را وصل می‌کند.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        if (!ProfileManager.loadAutoConnect(context)) return
        val link = ProfileStore.activeServerLink(context)
        if (link.isNullOrBlank()) return

        val i = Intent(context, SchnellVpnService::class.java).apply {
            this.action = SchnellVpnService.ACTION_CONNECT
            putExtra(SchnellVpnService.EXTRA_LINK, link)
        }
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            Log.w("BootReceiver", "auto-connect failed: ${e.message}")
        }
    }
}
