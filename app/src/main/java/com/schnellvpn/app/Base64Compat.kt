package com.schnellvpn.app

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin Base64 decoder.
 * - accepts standard ("+/") and URL-safe ("-_") alphabets
 * - padding is optional, whitespace/newlines are ignored
 * - returns null on invalid input (never throws)
 *
 * Written without android.util.Base64 so the parsers can be unit-tested on a plain JVM.
 */
object Base64Compat {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val table = IntArray(128) { -1 }.also { t ->
        ALPHABET.forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62
        t['_'.code] = 63
    }

    fun decode(input: String): ByteArray? {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        var count = 0
        for (ch in input) {
            if (ch == '=') break
            if (ch.isWhitespace()) continue
            val v = if (ch.code < 128) table[ch.code] else -1
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xFFFF
            bits += 6
            count++
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        if (count % 4 == 1) return null // impossible Base64 length
        return out.toByteArray()
    }

    fun decodeToString(input: String): String? = decode(input)?.toString(Charsets.UTF_8)
}
