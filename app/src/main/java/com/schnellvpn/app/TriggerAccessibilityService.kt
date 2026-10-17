package com.schnellvpn.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * با باز شدن اپ‌های انتخاب‌شده (وقتی VPN خاموش است)، VPN را خودکار روشن می‌کند.
 * برای کار کردن باید کاربر این سرویس دسترسی را در تنظیمات اندروید فعال کند.
 */
class TriggerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (VpnStatus.isConnected.value || VpnStatus.isConnecting.value) return

        val triggers = ProfileManager.loadTriggerApps(this)
        if (pkg !in triggers) return

        val link = ProfileStore.activeServerLink(this) ?: return
        val serviceIntent = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_CONNECT
            putExtra(SchnellVpnService.EXTRA_LINK, link)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (t: Throwable) {
            // اندروید ممکن است استارت FGS از پس‌زمینه را رد کند؛ با یک اکتیویتی شفاف دوباره تلاش می‌کنیم
            try {
                startActivity(Intent(this, AutoConnectActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(SchnellVpnService.EXTRA_LINK, link)
                })
            } catch (t2: Throwable) {
                Log.w("TriggerService", "auto-connect failed: ${t2.message}")
            }
        }
    }

    override fun onInterrupt() {}
}
