package com.schnellvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /** آیا گوشی اینترنت واقعی دارد؟ (برای جلوگیری از «اتصال» وقتی دیتا/وای‌فای خاموش است) */
    fun hasInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            // وقتی VPN فعال است، شبکه‌ی فعال خود VPN است و VALIDATED ممکن است نداشته باشد
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    /** آی‌پی‌های IPv4 محلی (برای نمایش IP هات‌اسپات). */
    fun localIpv4Addresses(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> Collections.list(nif.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it != "127.0.0.1" }
            .distinct()
            .sortedByDescending { if (it.startsWith("192.168.")) 1 else 0 }
    } catch (e: Exception) {
        emptyList()
    }

    /** آیا سرویس دسترسی (Accessibility) مربوط به روشن‌کردن خودکار VPN فعال است؟ */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cls = TriggerAccessibilityService::class.java
        val full = "${context.packageName}/${cls.name}"
        val short = "${context.packageName}/.${cls.simpleName}"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
