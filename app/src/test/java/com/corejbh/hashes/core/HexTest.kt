package com.corejbh.hashes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HexTest {

    private val genesisHash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

    @Test
    fun displayHexReversesTheDigest() {
        val digest = ByteArray(32)
        for (index in 0 until 32) {
            digest[index] = (31 - index).toByte()
        }
        val display = Hex.toDisplayHex(digest)
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", display)
    }

    @Test
    fun parseAndDisplayRoundTrip() {
        val digest = Hex.parse(genesisHash).reversedArray()
        assertEquals(genesisHash, Hex.toDisplayHex(digest))
        assertArrayEquals(Hex.parse(genesisHash), Hex.parse(genesisHash).copyOf())
    }

    @Test
    fun parseRejectsOddLength() {
        assertThrows(IllegalArgumentException::class.java) { Hex.parse("abc") }
    }

    @Test
    fun parseRejectsNonHex() {
        assertThrows(IllegalArgumentException::class.java) { Hex.parse("zz") }
    }
}
