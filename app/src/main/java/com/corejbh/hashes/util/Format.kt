package com.corejbh.hashes.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/**
 * Terminal formatting. Everything that reaches the screen is upper-case ASCII
 * and numbers are grouped with commas, so a reader can compare two lines of the
 * live stream at a glance.
 */
object Format {

    fun count(value: Long): String {
        val negative = value < 0
        val digits = abs(value).toString()
        val grouped = StringBuilder()
        for ((index, character) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(',')
            grouped.append(character)
        }
        return if (negative) "-$grouped" else grouped.toString()
    }

    fun hashRate(hashesPerSecond: Double): String = when {
        hashesPerSecond.isNaN() || hashesPerSecond.isInfinite() || hashesPerSecond <= 0.0 -> "0 H/s"
        hashesPerSecond >= 1e12 -> trim(hashesPerSecond / 1e12) + " TH/s"
        hashesPerSecond >= 1e9 -> trim(hashesPerSecond / 1e9) + " GH/s"
        hashesPerSecond >= 1e6 -> trim(hashesPerSecond / 1e6) + " MH/s"
        hashesPerSecond >= 1e3 -> trim(hashesPerSecond / 1e3) + " KH/s"
        else -> trim(hashesPerSecond) + " H/s"
    }

    /** Nonce as the terminal shows it: hex of the 32-bit value and the decimal. */
    fun nonce(value: Long): String {
        val unsigned = value and 0xffffffffL
        return "0x" + String.format(Locale.US, "%08X", unsigned) + " (" + count(unsigned) + ")"
    }

    /**
     * Very large or very small values as a short power of ten, everything else
     * with two decimals: 0.0042 -> "0.0042", 1.9e14 -> "1.90e14".
     */
    fun difficulty(value: BigDecimal): String {
        if (value <= BigDecimal.ZERO) return "0"
        if (value < BigDecimal("1000")) {
            return value.round(MathContext(3, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        }
        if (value < BigDecimal("1000000")) {
            return count(value.setScale(0, RoundingMode.HALF_UP).toLong())
        }
        val exponent = value.precision() - value.scale() - 1
        val mantissa = value.movePointLeft(exponent).round(MathContext(3, RoundingMode.HALF_UP))
        return mantissa.stripTrailingZeros().toPlainString().let { text ->
            if (text.contains('.')) text else "$text.0"
        } + "e" + exponent
    }

    /** Hashes as a count at a few significant digits, e.g. "1.90e14". */
    fun hashes(value: BigDecimal): String = difficulty(value)

    /**
     * The ODDS value: `1 IN 6.23e23`, and the expected time in brackets only
     * while the engine is hashing at a rate worth measuring.
     *
     * A stopped session keeps reporting the rate it decayed to, and the time for
     * the network difficulty at that rate grows past any number a reader can use
     * (`1.59e79 YEARS`). The odds stand on their own; the bracket says nothing
     * there, so it is left off instead of pushing a number across two lines.
     */
    fun odds(
        expectedHashes: BigDecimal,
        seconds: Double,
        mining: Boolean,
        hashesPerSecond: Double,
    ): String {
        val head = "1 IN " + hashes(expectedHashes)
        if (!mining || hashesPerSecond < MEASURABLE_HASH_RATE) return head
        return "$head (" + duration(seconds) + ")"
    }

    /** Below one hash per second the rate is decay, not a measurement. */
    private const val MEASURABLE_HASH_RATE = 1.0

    /** Seconds as years, days, hours, minutes or seconds. */
    fun duration(seconds: Double): String = when {
        seconds.isNaN() || seconds < 0 -> "?"
        seconds.isInfinite() -> "FOREVER"
        seconds >= 31_557_600 -> trim(seconds / 31_557_600) + " YEARS"
        seconds >= 86_400 -> trim(seconds / 86_400) + " DAYS"
        seconds >= 3_600 -> trim(seconds / 3_600) + " HOURS"
        seconds >= 60 -> trim(seconds / 60) + " MIN"
        else -> trim(seconds) + " SEC"
    }

    /** Trims a long hex string to `head...tail` for a fixed-width terminal row. */
    fun abbreviate(text: String, head: Int, tail: Int): String {
        if (text.length <= head + tail + 3) return text
        return text.substring(0, head) + "..." + text.substring(text.length - tail)
    }

    fun hex(value: ByteArray): String = com.corejbh.hashes.crypto.Hex.encode(value)

    private fun trim(value: Double): String {
        if (abs(value) >= 1e6) return powerOfTen(value)
        if (abs(value) >= 1000) return String.format(Locale.US, "%.0f", value)
        if (abs(value) >= 100) return String.format(Locale.US, "%.1f", value)
        if (abs(value) >= 10) return String.format(Locale.US, "%.2f", value)
        if (abs(value) >= 1) return String.format(Locale.US, "%.2f", value)
        return String.format(Locale.US, "%.3f", value)
    }

    /**
     * A value too large for a fixed-point row, as `3.17e72`: `%.0f` would print
     * every one of its digits and wrap the row onto a second line. The exponent
     * loses `%e`'s sign and zero padding so it matches `difficulty`'s `1.9e14`.
     */
    private fun powerOfTen(value: Double): String {
        val text = String.format(Locale.US, "%.2e", value)
        val marker = text.indexOf('e')
        val exponent = text.substring(marker + 1)
            .removePrefix("+")
            .trimStart('0')
            .ifEmpty { "0" }
        return text.substring(0, marker) + "e" + exponent
    }
}
