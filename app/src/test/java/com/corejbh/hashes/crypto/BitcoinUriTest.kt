package com.corejbh.hashes.crypto

import com.corejbh.hashes.TestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitcoinUriTest {

    @Test
    fun `returns a plain address unchanged`() {
        assertEquals(
            TestVectors.ADDRESS_P2WPKH,
            BitcoinUri.extractAddress(TestVectors.ADDRESS_P2WPKH),
        )
    }

    @Test
    fun `strips the bitcoin scheme and the query string`() {
        assertEquals(
            TestVectors.ADDRESS_P2WPKH,
            BitcoinUri.extractAddress("bitcoin:${TestVectors.ADDRESS_P2WPKH}?amount=0.001&label=Donation"),
        )
    }

    @Test
    fun `accepts an upper case scheme`() {
        assertEquals(
            TestVectors.ADDRESS_P2TR,
            BitcoinUri.extractAddress("BITCOIN:${TestVectors.ADDRESS_P2TR}?amount=1"),
        )
    }

    @Test
    fun `accepts the double slash form`() {
        assertEquals(
            TestVectors.ADDRESS_P2PKH,
            BitcoinUri.extractAddress("bitcoin://${TestVectors.ADDRESS_P2PKH}"),
        )
    }

    @Test
    fun `drops everything after a fragment`() {
        assertEquals(
            TestVectors.ADDRESS_P2SH,
            BitcoinUri.extractAddress("bitcoin:${TestVectors.ADDRESS_P2SH}#fragment"),
        )
    }

    @Test
    fun `returns null for a uri without an address`() {
        assertNull(BitcoinUri.extractAddress("bitcoin:?amount=1"))
        assertNull(BitcoinUri.extractAddress("   "))
    }

    @Test
    fun `decodes a percent encoded uri and then drops the query`() {
        val encoded = "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4%3Famount%3D1"
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            BitcoinUri.extractAddress(encoded),
        )
    }

    @Test
    fun `a scanned uri still validates`() {
        val address = BitcoinUri.extractAddress(
            "bitcoin:${TestVectors.ADDRESS_P2WPKH}?amount=0.001"
        )
        assertEquals(TestVectors.ADDRESS_P2WPKH, address)
        assertEquals(
            BitcoinAddressValidator.AddressType.P2WPKH,
            (BitcoinAddressValidator.validate(address!!) as BitcoinAddressValidator.Result.Valid).type,
        )
    }
}
