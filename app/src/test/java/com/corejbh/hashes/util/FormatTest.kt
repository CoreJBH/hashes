package com.corejbh.hashes.util

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `groups counts with commas`() {
        assertEquals("0", Format.count(0))
        assertEquals("999", Format.count(999))
        assertEquals("1,234,567", Format.count(1_234_567))
        assertEquals("-1,234", Format.count(-1_234))
    }

    @Test
    fun `prints a nonce in hex and decimal`() {
        assertEquals("0x003FEB48 (4,189,000)", Format.nonce(0x003FEB48L))
        assertEquals("0xFFFFFFFF (4,294,967,295)", Format.nonce(0xFFFFFFFFL))
    }

    @Test
    fun `scales hash rates`() {
        assertEquals("0 H/s", Format.hashRate(0.0))
        assertEquals("999.0 H/s", Format.hashRate(999.0))
        assertEquals("1.50 KH/s", Format.hashRate(1_500.0))
        assertEquals("2.50 MH/s", Format.hashRate(2_500_000.0))
        assertEquals("1.20 GH/s", Format.hashRate(1_200_000_000.0))
    }

    @Test
    fun `formats durations`() {
        assertEquals("30.00 SEC", Format.duration(30.0))
        assertEquals("1.00 MIN", Format.duration(60.0))
        assertEquals("1.00 HOURS", Format.duration(3_600.0))
        assertEquals("1.00 DAYS", Format.duration(86_400.0))
        assertEquals("1.00 YEARS", Format.duration(31_557_600.0))
        assertEquals("FOREVER", Format.duration(Double.POSITIVE_INFINITY))
        assertEquals("?", Format.duration(-1.0))
    }

    /** A decayed hash rate turns the expected time into a number no row can hold. */
    @Test
    fun `keeps an oversized duration to one power of ten`() {
        assertEquals("3.17e72 YEARS", Format.duration(1e80))
        assertEquals("5.70e300 YEARS", Format.duration(Double.MAX_VALUE))
    }

    @Test
    fun `switches to a power of ten at a million years`() {
        assertEquals("999999 YEARS", Format.duration(999_999 * 31_557_600.0))
        assertEquals("1.00e6 YEARS", Format.duration(1e6 * 31_557_600.0))
        assertEquals("316.9 YEARS", Format.duration(1e10))
    }

    @Test
    fun `shows the expected time only while the rate is worth measuring`() {
        val hashes = BigDecimal("6.23e23")
        val odds = "1 IN 6.23e23"
        assertEquals(odds, Format.odds(hashes, 1e10, mining = false, hashesPerSecond = 1e10))
        assertEquals(odds, Format.odds(hashes, 1e10, mining = true, hashesPerSecond = 0.9))
        assertEquals(odds, Format.odds(hashes, 1e10, mining = true, hashesPerSecond = 0.0))
        assertEquals(
            "1 IN 6.23e23 (316.9 YEARS)",
            Format.odds(hashes, 1e10, mining = true, hashesPerSecond = 1.0),
        )
    }

    @Test
    fun `keeps a stopped odds line short at any hash rate`() {
        val line = Format.odds(
            expectedHashes = BigDecimal("1e28"),
            seconds = 1e80,
            mining = false,
            hashesPerSecond = 1e-10,
        )
        assertEquals("1 IN 1.0e28", line)
    }

    @Test
    fun `formats small difficulties plainly`() {
        assertEquals("0.001", Format.difficulty(BigDecimal("0.001")))
        assertEquals("12.5", Format.difficulty(BigDecimal("12.5")))
        assertEquals("250,000", Format.difficulty(BigDecimal("250000")))
    }

    @Test
    fun `formats large difficulties as a power of ten`() {
        assertEquals("1.9e14", Format.difficulty(BigDecimal("1.9E+14")))
        assertEquals("1.0e10", Format.difficulty(BigDecimal("1E+10")))
    }

    @Test
    fun `abbreviates long hex without losing both ends`() {
        val text = "a".repeat(64)
        assertEquals("aaaaaa...aaaaaa", Format.abbreviate(text, 6, 6))
        assertEquals("short", Format.abbreviate("short", 6, 6))
    }
}
