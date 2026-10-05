package com.schnellvpn.app

/**
 * یک پروفایل مستقل: مجموعه‌سرورهای خودش + سرور انتخابی + لینک‌های اشتراک خودش.
 * این‌طور می‌توان چند اشتراک را جدا از هم نگه داشت (پروفایل ۱، ۲، ۳ …).
 */
data class ServerProfile(
    val id: Int,
    val name: String,
    val servers: List<VpnServer>,
    val selectedServerId: Int,
    val subscriptionUrls: List<String>
)
