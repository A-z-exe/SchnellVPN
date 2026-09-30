package com.schnellvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64CompatTest {

    @Test
    fun decodesStandardWithPadding() {
        assertEquals("hello", Base64Compat.decodeToString("aGVsbG8="))
    }

    @Test
    fun decodesWithoutPadding() {
        assertEquals("hello", Base64Compat.decodeToString("aGVsbG8"))
        assertEquals("hi", Base64Compat.decodeToString("aGk"))
    }

    @Test
    fun decodesUrlSafeAlphabet() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte())
        val std = java.util.Base64.getEncoder().encodeToString(bytes)   // "+//+"
        val url = java.util.Base64.getUrlEncoder().encodeToString(bytes) // "-__-"
        assertEquals(bytes.toList(), Base64Compat.decode(std)!!.toList())
        assertEquals(bytes.toList(), Base64Compat.decode(url)!!.toList())
    }

    @Test
    fun ignoresWhitespaceAndNewlines() {
        assertEquals("hello world", Base64Compat.decodeToString("aGVs bG8g\nd29y\r\nbGQ="))
    }

    @Test
    fun roundTripsUnicode() {
        val text = "سرور آزمایشی ✅"
        val enc = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, Base64Compat.decodeToString(enc))
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(Base64Compat.decode("not base64!!"))
        assertNull(Base64Compat.decode("a")) // impossible length
        assertNull(Base64Compat.decode("vless://abc"))
    }
}
