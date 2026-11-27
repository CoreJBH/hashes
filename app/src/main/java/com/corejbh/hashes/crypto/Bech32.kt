package com.corejbh.hashes.crypto

/**
 * Bech32 / Bech32m as specified in BIP-173 and BIP-350.
 *
 * Only decoding is needed: the app never builds an address, it checks the one a
 * user pastes. Both checksum constants are implemented because the witness
 * version decides which one applies - version 0 uses Bech32, versions 1 to 16
 * use Bech32m. Getting that split wrong would accept a version 1 address whose
 * checksum was built the old way, which is exactly the class of typo BIP-350
 * exists to catch.
 */
object Bech32 {

    const val CHECKSUM_BECH32 = 1
    const val CHECKSUM_BECH32M = 0x2bc830a3

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private val GENERATOR = intArrayOf(
        0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    )

    data class Decoded(val hrp: String, val data: IntArray, val checksum: Int)

    sealed interface Result {
        data class Ok(val decoded: Decoded) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Decodes [address] with the full BIP-173 rules: length limits, allowed
     * characters, no mixed case, and a checksum that is Bech32 or Bech32m.
     */
    fun decode(address: String): Result {
        if (address.length < 8 || address.length > 90) {
            return Result.Failed("length")
        }
        var hasLower = false
        var hasUpper = false
        for (character in address) {
            if (character.code < 33 || character.code > 126) return Result.Failed("character")
            if (character in 'a'..'z') hasLower = true
            if (character in 'A'..'Z') hasUpper = true
        }
        if (hasLower && hasUpper) return Result.Failed("mixed case")
        val normalized = address.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator < 1 || separator + 7 > normalized.length) return Result.Failed("separator")
        val hrp = normalized.substring(0, separator)
        val payload = normalized.substring(separator + 1)
        val values = IntArray(payload.length)
        for (index in payload.indices) {
            val value = CHARSET.indexOf(payload[index])
            if (value < 0) return Result.Failed("character")
            values[index] = value
        }
        val checksumValue = polymod(hrpExpand(hrp) + values.toList())
        val checksum = when (checksumValue) {
            CHECKSUM_BECH32 -> CHECKSUM_BECH32
            CHECKSUM_BECH32M -> CHECKSUM_BECH32M
            else -> return Result.Failed("checksum")
        }
        return Result.Ok(Decoded(hrp, values.copyOfRange(0, values.size - 6), checksum))
    }

    /** Regroups 5-bit values into 8-bit bytes; null when padding is invalid. */
    fun toBytes(values: IntArray): ByteArray? {
        var accumulator = 0
        var bits = 0
        val out = ArrayList<Byte>(values.size * 5 / 8 + 1)
        for (value in values) {
            if (value < 0 || value > 31) return null
            accumulator = (accumulator shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((accumulator ushr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5 || ((accumulator shl (8 - bits)) and 0xff) != 0) return null
        return out.toByteArray()
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val out = ArrayList<Int>(hrp.length * 2 + 1)
        for (character in hrp) out.add(character.code ushr 5)
        out.add(0)
        for (character in hrp) out.add(character.code and 31)
        return out
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (index in 0 until 5) {
                if ((top ushr index) and 1 == 1) checksum = checksum xor GENERATOR[index]
            }
        }
        return checksum
    }
}
