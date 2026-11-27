package com.corejbh.hashes.crypto

/** Base58 decoding and Base58Check verification for legacy Bitcoin addresses. */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Decodes [text] to its byte payload plus the four checksum bytes, verifying
     * the checksum. Returns null for any character outside the alphabet or a
     * payload shorter than the checksum itself.
     */
    fun decodeChecked(text: String): ByteArray? {
        val raw = decode(text) ?: return null
        if (raw.size < 5) return null
        val payload = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = Sha256.doubleHash(payload)
        for (index in 0 until 4) {
            if (checksum[index] != expected[index]) return null
        }
        return payload
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        var value = java.math.BigInteger.ZERO
        val radix = java.math.BigInteger.valueOf(58)
        for (character in text) {
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) return null
            value = value.multiply(radix).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        var bytes = value.toByteArray()
        // BigInteger writes a sign byte for the top bit being set; drop it.
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        if (value.signum() == 0) bytes = ByteArray(0)
        var leadingZeros = 0
        while (leadingZeros < text.length && text[leadingZeros] == '1') leadingZeros++
        val out = ByteArray(leadingZeros + bytes.size)
        System.arraycopy(bytes, 0, out, leadingZeros, bytes.size)
        return out
    }
}
