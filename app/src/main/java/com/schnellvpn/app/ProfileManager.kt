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
                        pingMs = obj.getInt("pingMs").takeIf { it >= 0 }
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

    // ========== تنظیمات ==========

    fun saveAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    fun loadAutoConnect(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_CONNECT, false)
    }

    // ========== آمار ==========

    fun hasServers(context: Context): Boolean {
        return loadServers(context).isNotEmpty()
    }

    fun serverCount(context: Context): Int {
        return loadServers(context).size
    }
}
