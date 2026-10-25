package com.schnellvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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

            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true
                }
            }

            // اگر شبکه‌ی فعال مشخص نبود، بین همه‌ی شبکه‌ها دنبال اینترنت بگرد
            cm.allNetworks.any { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (e: SecurityException) {
            // اگر مجوز وضعیت شبکه داده نشده بود، کاربر را بلاک نکن؛ TunnelProbe درستی مسیر را چک می‌کند
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * شبکه‌ی واقعیِ زیرین (Wi‑Fi / دیتا) — نه VPN.
     * برای پینگ استفاده می‌شود تا تست پینگ از داخل تونل VPN رد نشود و عدد فیک ندهد.
     */
    fun underlyingNetwork(context: Context): Network? {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (
                        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
            }
        } catch (e: Exception) {
            null
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
