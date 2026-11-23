package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetTest {

    private val assets = listOf(
        "SchnellVPN-1.6.0-armeabi-v7a.apk",
        "SchnellVPN-1.6.0-arm64-v8a.apk",
        "SchnellVPN-1.6.0-x86.apk",
        "SchnellVPN-1.6.0-x86_64.apk"
    )

    @Test
    fun picks_the_devices_primary_abi() {
        assertEquals(
            "SchnellVPN-1.6.0-arm64-v8a.apk",
            UpdateManager.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            "SchnellVPN-1.6.0-x86_64.apk",
            UpdateManager.pickApk(assets, listOf("x86_64", "x86"))
        )
    }

    @Test
    fun falls_back_to_second_supported_abi() {
        assertEquals(
            "SchnellVPN-1.6.0-armeabi-v7a.apk",
            UpdateManager.pickApk(assets, listOf("mips", "armeabi-v7a"))
        )
    }

    @Test
    fun uses_single_apk_releases_without_abi_suffix() {
        val old = listOf("SchnellVPN-1.5.5.apk")
        assertEquals("SchnellVPN-1.5.5.apk", UpdateManager.pickApk(old, listOf("arm64-v8a")))
    }

    @Test
    fun no_match_is_null() {
        assertNull(UpdateManager.pickApk(emptyList(), listOf("arm64-v8a")))
        assertNull(UpdateManager.pickApk(listOf("SchnellVPN-1.6.0-x86.apk"), listOf("arm64-v8a")))
    }
}
