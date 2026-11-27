package com.corejbh.hashes.crypto

import com.corejbh.hashes.TestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinAddressValidatorTest {

    private fun validate(text: String) = BitcoinAddressValidator.validate(text)

    @Test
    fun `accepts a mainnet p2pkh address`() {
        val result = validate(TestVectors.ADDRESS_P2PKH)
        assertTrue(result is BitcoinAddressValidator.Result.Valid)
        assertEquals(
            BitcoinAddressValidator.AddressType.P2PKH,
            (result as BitcoinAddressValidator.Result.Valid).type,
        )
    }

    @Test
    fun `accepts a mainnet p2sh address`() {
        val result = validate(TestVectors.ADDRESS_P2SH)
        assertTrue(result is BitcoinAddressValidator.Result.Valid)
        assertEquals(
            BitcoinAddressValidator.AddressType.P2SH,
            (result as BitcoinAddressValidator.Result.Valid).type,
        )
    }

    @Test
    fun `accepts p2wpkh and p2wsh bech32 addresses`() {
        assertEquals(
            BitcoinAddressValidator.AddressType.P2WPKH,
            (validate(TestVectors.ADDRESS_P2WPKH) as BitcoinAddressValidator.Result.Valid).type,
        )
        assertEquals(
            BitcoinAddressValidator.AddressType.P2WSH,
            (validate(TestVectors.ADDRESS_P2WSH) as BitcoinAddressValidator.Result.Valid).type,
        )
    }

    @Test
    fun `accepts a taproot bech32m address`() {
        assertEquals(
            BitcoinAddressValidator.AddressType.P2TR,
            (validate(TestVectors.ADDRESS_P2TR) as BitcoinAddressValidator.Result.Valid).type,
        )
    }

    @Test
    fun `accepts an all upper case address`() {
        val upper = TestVectors.ADDRESS_P2WPKH.uppercase()
        assertTrue(validate(upper) is BitcoinAddressValidator.Result.Valid)
    }

    @Test
    fun `rejects a mixed case bech32 address`() {
        val mixed = "bc1QW508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertEquals(
            BitcoinAddressValidator.Error.MIXED_CASE,
            (validate(mixed) as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `rejects a bech32 address with a broken checksum`() {
        val broken = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3tq"
        assertEquals(
            BitcoinAddressValidator.Error.BAD_BECH32_CHECKSUM,
            (validate(broken) as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `rejects a base58 address with a broken checksum`() {
        val broken = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"
        assertEquals(
            BitcoinAddressValidator.Error.BAD_BASE58_CHECKSUM,
            (validate(broken) as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `rejects testnet addresses of every form`() {
        val addresses = listOf(
            "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn",
            "2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc",
            "tb1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnl25zw8",
            "tb1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0slua5fd",
            "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080",
        )
        for (address in addresses) {
            val result = validate(address)
            assertTrue("$address should be refused", result is BitcoinAddressValidator.Result.Invalid)
            assertEquals(
                BitcoinAddressValidator.Error.TESTNET,
                (result as BitcoinAddressValidator.Result.Invalid).error,
            )
        }
    }

    @Test
    fun `rejects a witness version zero address with a bech32m checksum`() {
        val address = "bc1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnqslask"
        val result = validate(address) as BitcoinAddressValidator.Result.Invalid
        assertEquals(BitcoinAddressValidator.Error.WRONG_CHECKSUM_TYPE, result.error)
    }

    @Test
    fun `rejects a witness version one address with a bech32 checksum`() {
        val address = "bc1pqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0sagmhkq"
        val result = validate(address) as BitcoinAddressValidator.Result.Invalid
        assertEquals(BitcoinAddressValidator.Error.WRONG_CHECKSUM_TYPE, result.error)
    }

    @Test
    fun `rejects a version zero program of the wrong length`() {
        val address = "bc1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzsf6edgu"
        val result = validate(address) as BitcoinAddressValidator.Result.Invalid
        assertEquals(BitcoinAddressValidator.Error.BAD_PROGRAM_LENGTH, result.error)
    }

    @Test
    fun `accepts an unknown witness version with a valid program`() {
        val address = "bc1zqqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0sqfj5af"
        val result = validate(address)
        assertTrue(result is BitcoinAddressValidator.Result.Valid)
        assertEquals(
            BitcoinAddressValidator.AddressType.WITNESS_V1_PLUS,
            (result as BitcoinAddressValidator.Result.Valid).type,
        )
    }

    @Test
    fun `rejects an empty input`() {
        assertEquals(
            BitcoinAddressValidator.Error.EMPTY,
            (validate("   ") as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `rejects text that is not an address`() {
        assertEquals(
            BitcoinAddressValidator.Error.UNKNOWN_FORMAT,
            (validate("hello world") as BitcoinAddressValidator.Result.Invalid).error,
        )
    }

    @Test
    fun `trims surrounding whitespace before validating`() {
        assertTrue(validate("  " + TestVectors.ADDRESS_P2WPKH + "\n") is BitcoinAddressValidator.Result.Valid)
    }
}
