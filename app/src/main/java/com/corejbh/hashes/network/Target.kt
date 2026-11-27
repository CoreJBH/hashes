package com.corejbh.hashes.network

import com.corejbh.hashes.crypto.Hex
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/**
 * Mainnet targets and difficulty arithmetic.
 *
 * The network sends its target in Bitcoin's compact form (`nbits`). A hash meets
 * the target when the 256-bit number it represents - the digest read
 * little-endian, which is why a solved block prints as leading zeros - is at or
 * below the target.
 */
object Target {

    /** The difficulty 1 target used by pools: 0x00000000FFFF0000... (bdiff). */
    val DIFFICULTY_ONE: BigInteger =
        BigInteger("00000000ffff0000000000000000000000000000000000000000000000000000", 16)

    private val TWO_POW_32: BigDecimal = BigDecimal.valueOf(4_294_967_296.0)

    /** Expands a 4-byte compact target (`1b04864c`) into the full 32-byte target. */
    fun fromCompact(nbitsHex: String): BigInteger {
        require(Hex.isHex(nbitsHex, 8)) { "nbits is 4 bytes of hex" }
        val bytes = Hex.decode(nbitsHex)
        // Compact form: the most significant byte is the exponent, the other
        // three are the mantissa (0x1b04864c -> 0x04864c * 256^(0x1b - 3)).
        val exponent = bytes[0].toInt() and 0xff
        val mantissa = ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        val value = BigInteger.valueOf(mantissa.toLong())
        return when {
            exponent <= 3 -> value.shiftRight(8 * (3 - exponent))
            else -> value.shiftLeft(8 * (exponent - 3))
        }
    }

    /** The target as 32 big-endian bytes, the form the native core compares against. */
    fun toBytes(target: BigInteger): ByteArray {
        require(target.signum() >= 0) { "a target is not negative" }
        val big = target.toByteArray()
        val out = ByteArray(32)
        val useful = if (big.size > 32) big.copyOfRange(big.size - 32, big.size) else big
        System.arraycopy(useful, 0, out, 32 - useful.size, useful.size)
        return out
    }

    fun fromBytes(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    /** Network difficulty for a compact target. */
    fun difficulty(nbitsHex: String): BigDecimal {
        val target = fromCompact(nbitsHex)
        if (target.signum() == 0) return BigDecimal(Double.MAX_VALUE)
        return BigDecimal(DIFFICULTY_ONE).divide(BigDecimal(target), MathContext.DECIMAL64)
    }

    /**
     * Difficulty a found hash represents, the number shown as a share's value.
     * The digest is read little-endian; an all-zero digest is treated as the
     * largest possible value.
     */
    fun hashDifficulty(hash: ByteArray): BigDecimal {
        require(hash.size == 32) { "a hash is 32 bytes" }
        val value = BigInteger(1, hash.reversedArray())
        if (value.signum() == 0) return BigDecimal(Double.MAX_VALUE)
        return BigDecimal(DIFFICULTY_ONE).divide(BigDecimal(value), MathContext.DECIMAL64)
    }

    fun meetsTarget(hash: ByteArray, target: BigInteger): Boolean =
        BigInteger(1, hash.reversedArray()) <= target

    /** Leading zero hex digits of the displayed hash, the terminal's own measure. */
    fun leadingZeroDigits(hash: ByteArray): Int {
        val display = Hex.encode(hash.reversedArray())
        var count = 0
        while (count < display.length && display[count] == '0') count++
        return count
    }

    /** Expected hashes for one block at a given network difficulty (difficulty * 2^32). */
    fun expectedHashes(networkDifficulty: BigDecimal): BigDecimal =
        networkDifficulty.multiply(TWO_POW_32, MathContext.DECIMAL64)

    /** Seconds to one block for a hash rate, if the rate is above zero. */
    fun expectedSeconds(networkDifficulty: BigDecimal, hashesPerSecond: Double): Double {
        if (hashesPerSecond <= 0.0) return Double.POSITIVE_INFINITY
        return expectedHashes(networkDifficulty).toDouble() / hashesPerSecond
    }

    /** `0x1b04864c` in the terminal's compact form, with the exponent in brackets. */
    fun describeCompact(nbitsHex: String): String =
        "0x$nbitsHex (" + difficulty(nbitsHex).round(MathContext(6)).toPlainString() + ")"
}
