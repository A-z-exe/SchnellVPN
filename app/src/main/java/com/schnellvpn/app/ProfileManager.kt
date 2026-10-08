package com.schnellvpn.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * مدیریت ذخیره و بازیابی سرورها و تنظیمات
 * از SharedPreferences برای ذخیره‌سازی دائمی استفاده می‌کند
 */
object ProfileManager {

    private const val PREFS_NAME = "schnellvpn_profiles"
    private const val KEY_SERVERS = "servers"
    private const val KEY_LAST_SERVER_ID = "last_server_id"
    private const val KEY_SUBSCRIPTION_URL = "subscription_url"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
    private const val KEY_SUBSCRIPTION_URLS = "subscription_urls"
    private const val KEY_GLASS = "glass_theme"
    private const val KEY_DISCONNECT_ON_LOCK = "disconnect_on_lock"
    private const val KEY_HOTSPOT_SHARE = "hotspot_share"
    private const val KEY_EXCLUDED_APPS = "excluded_apps"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== سرورها ==========

    fun saveServers(context: Context, servers: List<VpnServer>) {
        val array = JSONArray()
        servers.forEach { server ->
            val obj = JSONObject().apply {
                put("id", server.id)
                put("flag", server.flag)
                put("name", server.name)
                put("protocolLabel", server.protocolLabel)
                put("link", server.link)
                put("pingMs", server.pingMs ?: -1)
                put("source", server.source)
            }
            array.put(obj)
        }
        prefs(context).edit()
            .putString(KEY_SERVERS, array.toString())
            .apply()
    }

    fun loadServers(context: Context): List<VpnServer> {
        val json = prefs(context).getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val result = mutableListOf<VpnServer>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    VpnServer(
                        id = obj.getInt("id"),
                        flag = obj.getString("flag"),
                        name = obj.getString("name"),
                        protocolLabel = obj.getString("protocolLabel"),
                        link = obj.getString("link"),
                        pingMs = obj.getInt("pingMs").takeIf { it >= 0 },
                        source = obj.optString("source", "")
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearServers(context: Context) {
        prefs(context).edit()
            .remove(KEY_SERVERS)
            .remove(KEY_SELECTED_SERVER_ID)
            .apply()
    }

    // ========== سرور انتخاب شده ==========

    fun saveSelectedServerId(context: Context, id: Int) {
        prefs(context).edit()
            .putInt(KEY_SELECTED_SERVER_ID, id)
            .apply()
    }

    fun loadSelectedServerId(context: Context): Int {
        return prefs(context).getInt(KEY_SELECTED_SERVER_ID, -1)
    }

    fun loadSelectedServer(context: Context): VpnServer? {
        val id = loadSelectedServerId(context)
        if (id == -1) return null
        return loadServers(context).find { it.id == id }
    }

    // ========== لینک Subscription ==========

    fun saveSubscriptionUrl(context: Context, url: String) {
        prefs(context).edit()
            .putString(KEY_SUBSCRIPTION_URL, url)
            .apply()
    }

    fun loadSubscriptionUrl(context: Context): String {
        return prefs(context).getString(KEY_SUBSCRIPTION_URL, "") ?: ""
    }

    /** همه‌ی لینک‌های اشتراکی ذخیره‌شده (برای دکمه‌ی بروزرسانی). */
    fun loadSubscriptionUrls(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SUBSCRIPTION_URLS, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                val list = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // خراب بود → می‌افتیم روی لینک تکی قدیمی
            }
        }
        val legacy = loadSubscriptionUrl(context)
        return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
    }

    fun addSubscriptionUrl(context: Context, url: String) {
        val current = loadSubscriptionUrls(context)
        if (url in current) return
        val arr = JSONArray()
        (current + url).forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_SUBSCRIPTION_URLS, arr.toString()).apply()
    }

    fun clearSubscriptionUrls(context: Context) {
        prefs(context).edit()
            .remove(KEY_SUBSCRIPTION_URLS)
            .remove(KEY_SUBSCRIPTION_URL)
            .apply()
    }

    // ========== تنظیمات ==========

    fun saveGlass(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASS, enabled).apply()
    }

    fun loadGlass(context: Context): Boolean = prefs(context).getBoolean(KEY_GLASS, true)

    fun saveAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    fun loadAutoConnect(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_CONNECT, false)
    }

    fun saveDisconnectOnLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCONNECT_ON_LOCK, enabled).apply()
    }

    fun loadDisconnectOnLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCONNECT_ON_LOCK, false)

    fun saveHotspotShare(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HOTSPOT_SHARE, enabled).apply()
    }

    fun loadHotspotShare(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HOTSPOT_SHARE, false)

    /** پکیج‌هایی که نباید از VPN استفاده کنند (split tunneling). */
    fun saveExcludedPackages(context: Context, packages: Set<String>) {
        val arr = JSONArray()
        packages.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_EXCLUDED_APPS, arr.toString()).apply()
    }

    fun loadExcludedPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_EXCLUDED_APPS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ========== آمار ==========

    fun hasServers(context: Context): Boolean {
        return loadServers(context).isNotEmpty()
    }

    fun serverCount(context: Context): Int {
        return loadServers(context).size
    }
}
