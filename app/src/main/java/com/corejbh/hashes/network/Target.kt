package com.corejbh.hashes.network

import com.corejbh.hashes.crypto.Hex
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

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

    /** The easiest target a 256-bit hash can beat: every bit set. */
    val MAX_TARGET: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    private val TWO_POW_32: BigDecimal = BigDecimal.valueOf(4_294_967_296.0)

    /**
     * Enough digits that dividing [DIFFICULTY_ONE] by a pool difficulty keeps the
     * integer part exact: the dividend already has 68 digits, so the quotient for
     * any difficulty above about 1e-12 is still whole.
     */
    private val SHARE_MATH = MathContext(80, RoundingMode.DOWN)

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

    /**
     * The target a pool's share difficulty stands for: `DIFFICULTY_ONE / difficulty`.
     *
     * A share is found against the pool's difficulty, not the network's. The
     * network target is what a block has to beat and is far out of reach of a
     * phone, so an engine given it finds nothing to submit for days; the pool's
     * own difficulty is the bar a share actually has to clear.
     *
     * The quotient is clamped to the 256-bit range. A difficulty below about
     * 2^-32 asks for a target wider than 256 bits, which no 32-byte value can
     * hold, so it becomes [MAX_TARGET] - the easiest target there is. A
     * difficulty high enough to push the target under one gives a target of one:
     * a zero target is one no hash meets, and a share target nothing can meet is
     * not a share target. A difficulty that is not a positive number (zero, a
     * negative, NaN, infinity) is read as difficulty one, the pool default,
     * because there is nothing to divide by.
     */
    fun fromDifficulty(difficulty: Double): BigInteger {
        if (!difficulty.isFinite() || difficulty <= 0.0) return DIFFICULTY_ONE
        val target = BigDecimal(DIFFICULTY_ONE)
            .divide(BigDecimal(difficulty), SHARE_MATH)
            .toBigInteger()
        return when {
            target > MAX_TARGET -> MAX_TARGET
            target.signum() == 0 -> BigInteger.ONE
            else -> target
        }
    }

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
