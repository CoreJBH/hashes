package com.corejbh.hashes.crypto

/** Lower-case hexadecimal helpers shared by the protocol, UI and tests. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(DIGITS[value ushr 4]).append(DIGITS[value and 0x0f])
        }
        return out.toString()
    }

    /** Decodes an even-length hex string; throws [IllegalArgumentException] otherwise. */
    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = digit(text[index * 2])
            val low = digit(text[index * 2 + 1])
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    fun decodeOrNull(text: String): ByteArray? = try {
        decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun isHex(text: String, length: Int? = null): Boolean {
        if (length != null && text.length != length) return false
        if (text.length % 2 != 0) return false
        for (character in text) {
            if (digitOrNull(character) == null) return false
        }
        return true
    }

    /** Reverses the byte order of a 32-byte hash given as hex. */
    fun reverseHex(text: String): String = encode(decode(text).reversedArray())

    /** Reverses each 4-byte word, which is how Stratum sends a previous block hash. */
    fun swapWords32(text: String): String {
        val bytes = decode(text)
        require(bytes.size % 4 == 0) { "a word-swapped hash is a multiple of 4 bytes" }
        val out = ByteArray(bytes.size)
        var index = 0
        while (index < bytes.size) {
            for (offset in 0 until 4) {
                out[index + offset] = bytes[index + 3 - offset]
            }
            index += 4
        }
        return encode(out)
    }

    /** Little-endian 32-bit hex, the order a block header stores version, time, bits and nonce. */
    fun littleEndian32(text: String): String = reverseHex(text)

    private fun digit(character: Char): Int =
        digitOrNull(character) ?: throw IllegalArgumentException("not a hex digit: $character")

    private fun digitOrNull(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> null
    }
}
