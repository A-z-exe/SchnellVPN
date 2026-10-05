package com.schnellvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ذخیره/بازیابی پروفایل‌های سرور (هر پروفایل = یک اشتراک/مجموعه‌سرور مستقل) در SharedPreferences.
 */
object ProfileStore {

    private const val PREFS_NAME = "schnellvpn_profiles"
    private const val KEY_PROFILES = "server_profiles_v1"
    private const val KEY_ACTIVE = "active_server_profile_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<ServerProfile> {
        val json = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<ServerProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ServerProfile(
                        id = o.optInt("id", i + 1),
                        name = o.optString("name", "پروفایل ${i + 1}"),
                        servers = parseServers(o.optJSONArray("servers")),
                        selectedServerId = o.optInt("selectedServerId", -1),
                        subscriptionUrls = parseStrings(o.optJSONArray("subscriptionUrls"))
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, profiles: List<ServerProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("selectedServerId", p.selectedServerId)
                    put("subscriptionUrls", JSONArray().apply { p.subscriptionUrls.forEach { put(it) } })
                    put(
                        "servers",
                        JSONArray().apply {
                            p.servers.forEach { s ->
                                put(
                                    JSONObject().apply {
                                        put("id", s.id)
                                        put("flag", s.flag)
                                        put("name", s.name)
                                        put("protocolLabel", s.protocolLabel)
                                        put("link", s.link)
                                        put("pingMs", s.pingMs ?: -1)
                                        put("source", s.source)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun loadActiveId(context: Context): Int = prefs(context).getInt(KEY_ACTIVE, -1)

    fun saveActiveId(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE, id).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PROFILES).remove(KEY_ACTIVE).apply()
    }

    private fun parseServers(arr: JSONArray?): List<VpnServer> {
        if (arr == null) return emptyList()
        val out = ArrayList<VpnServer>(arr.length())
        for (j in 0 until arr.length()) {
            val s = arr.optJSONObject(j) ?: continue
            out.add(
                VpnServer(
                    id = s.optInt("id", j + 1),
                    flag = s.optString("flag", "🌐"),
                    name = s.optString("name", "Server ${j + 1}"),
                    protocolLabel = s.optString("protocolLabel", ""),
                    link = s.optString("link", ""),
                    pingMs = s.optInt("pingMs", -1).takeIf { it >= 0 },
                    source = s.optString("source", "")
                )
            )
        }
        return out
    }

    private fun parseStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }
}
