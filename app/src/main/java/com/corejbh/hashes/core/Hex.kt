package com.corejbh.hashes.core

/** Hex helpers for block hashes and headers. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    /**
     * A SHA-256 digest as a block hash is written down: the digest bytes
     * reversed, because bitcoin reads the hash little-endian.
     */
    fun toDisplayHex(digest: ByteArray): String {
        val out = StringBuilder(digest.size * 2)
        for (index in digest.indices.reversed()) {
            val value = digest[index].toInt() and 0xFF
            out.append(DIGITS[value ushr 4]).append(DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    /** A digest in its own byte order, which is how SHA-256 itself prints one. */
    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(DIGITS[value ushr 4]).append(DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    fun parse(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = DIGITS.indexOf(text[2 * index].lowercaseChar())
            val low = DIGITS.indexOf(text[2 * index + 1].lowercaseChar())
            require(high >= 0 && low >= 0) { "not a hex string" }
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
