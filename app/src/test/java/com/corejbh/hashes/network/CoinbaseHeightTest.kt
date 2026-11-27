package com.corejbh.hashes.network

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.crypto.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinbaseHeightTest {

    @Test
    fun `reads the height of a modern segwit coinbase`() {
        val coinbase = Hex.decode(TestVectors.COINBASE_800000)
        assertEquals(TestVectors.HEIGHT_800000, CoinbaseHeight.parse(coinbase))
    }

    @Test
    fun `an old coinbase without a height push returns null`() {
        // Block 100000 predates BIP34: its scriptSig starts with the nbits, which
        // does not decode to a plausible height.
        val coinbase = Hex.decode(TestVectors.BLOCK_COINBASE)
        assertNull(CoinbaseHeight.parse(coinbase))
    }

    @Test
    fun `a truncated coinbase returns null`() {
        val coinbase = Hex.decode(TestVectors.COINBASE_800000)
        assertNull(CoinbaseHeight.parse(coinbase.copyOfRange(0, 12)))
        assertNull(CoinbaseHeight.parse(ByteArray(0)))
    }
}
