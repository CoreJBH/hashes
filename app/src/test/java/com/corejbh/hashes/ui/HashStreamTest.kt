package com.corejbh.hashes.ui

import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.TestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashStreamTest {

    @Test
    fun `keeps the newest samples first and caps the list`() {
        val stream = HashStream(capacity = 10)
        for (index in 0 until 15) {
            stream.push(index.toString(16).padStart(64, 'a'))
        }
        assertEquals(10, stream.size)
        assertEquals(10, stream.lines().size)
    }

    @Test
    fun `counts leading zeros and flags near misses`() {
        val stream = HashStream()
        val lines = stream.push("0000abcd".padEnd(64, 'f'))
        assertEquals(4, lines.first().leadingZeros)
        assertTrue(lines.first().worthHighlighting)

        val quiet = stream.push("00abcd00".padEnd(64, 'f'))
        assertEquals(2, quiet.first().leadingZeros)
        assertFalse(quiet.first().worthHighlighting)
    }

    @Test
    fun `a three zero hash is not highlighted`() {
        val stream = HashStream()
        val lines = stream.push("000abc".padEnd(64, '0'))
        assertFalse(lines.first().worthHighlighting)
    }

    @Test
    fun `lowercases samples and ignores empty input`() {
        val stream = HashStream()
        assertTrue(stream.push("").isEmpty())
        val lines = stream.push("ABCDEF".padEnd(64, '0'))
        assertEquals(lines.first().hex, lines.first().hex.lowercase())
    }

    @Test
    fun `clear empties the buffer`() {
        val stream = HashStream()
        stream.push("00".padEnd(64, '1'))
        stream.clear()
        assertEquals(0, stream.size)
    }
}

class AddressBadgeTest {

    private fun badgeOf(address: String): String =
        AddressBadge.of(BitcoinAddressValidator.validate(address)).badge

    @Test
    fun `valid addresses show the mainnet badge`() {
        assertEquals("[VALID_MAINNET]", badgeOf(TestVectors.ADDRESS_P2WPKH))
        assertEquals("[VALID_MAINNET]", badgeOf(TestVectors.ADDRESS_P2TR))
        assertEquals("[VALID_MAINNET]", badgeOf(TestVectors.ADDRESS_P2PKH))
    }

    @Test
    fun `an empty field shows the awaiting badge`() {
        assertEquals("[AWAITING_INPUT]", badgeOf(""))
    }

    @Test
    fun `a typo shows the checksum badge`() {
        assertEquals("[INVALID_CHECKSUM]", badgeOf("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3tq"))
        assertEquals("[INVALID_CHECKSUM]", badgeOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"))
    }

    @Test
    fun `a testnet address shows the rejection badge`() {
        assertEquals("[TESTNET_REJECTED]", badgeOf("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))
        assertEquals("[TESTNET_REJECTED]", badgeOf("tb1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnl25zw8"))
    }

    @Test
    fun `valid badges report the address type as detail`() {
        val line = AddressBadge.of(BitcoinAddressValidator.validate(TestVectors.ADDRESS_P2TR))
        assertEquals("P2TR", line.detail)
    }
}
