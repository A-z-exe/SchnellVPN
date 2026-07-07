package com.schnellvpn.app

/**
 * یک سرور VPN — لینکش می‌تونه vless:// یا vmess:// یا trojan:// یا ss:// باشه
 */
data class VpnServer(
    val id: Int,
    val flag: String,
    val name: String,
    val protocolLabel: String,
    val link: String,
    var pingMs: Int? = null
)
