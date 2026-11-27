package com.corejbh.hashes.crypto

import com.corejbh.hashes.TestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256Test {

    @Test
    fun `hashes the abc vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hex.encode(Sha256.hash("abc".toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `hashes the empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hex.encode(Sha256.hash(ByteArray(0))),
        )
    }

    @Test
    fun `double hashes the genesis header to the genesis hash`() {
        val header = Hex.decode(TestVectors.GENESIS_HEADER)
        val digest = Sha256.doubleHash(header)
        assertEquals(TestVectors.GENESIS_HASH, Hex.encode(digest.reversedArray()))
    }

    @Test
    fun `midstate path equals a plain double hash of the header`() {
        val header = Hex.decode(TestVectors.BLOCK_HEADER)
        val midstate = Sha256.midstate(header.copyOfRange(0, 64))
        val tail = header.copyOfRange(64, 80)
        assertEquals(
            Hex.encode(Sha256.doubleHash(header)),
            Hex.encode(Sha256.hash80WithMidstate(midstate, tail)),
        )
    }

    @Test
    fun `allocation free path equals a plain double hash`() {
        val header = Hex.decode(TestVectors.BLOCK_HEADER)
        val midstate = Sha256.midstate(header.copyOfRange(0, 64))
        val out = ByteArray(32)
        Sha256.doubleHash80(
            midstate = midstate,
            tail = header.copyOfRange(64, 80),
            stateScratch = IntArray(8),
            blockScratch = ByteArray(128),
            out = out,
        )
        assertEquals(Hex.encode(Sha256.doubleHash(header)), Hex.encode(out))
    }

    @Test
    fun `midstate changes the result when the nonce changes`() {
        val header = Hex.decode(TestVectors.BLOCK_HEADER)
        val midstate = Sha256.midstate(header.copyOfRange(0, 64))
        val tail = header.copyOfRange(64, 80)
        val first = Sha256.hash80WithMidstate(midstate, tail).copyOf()
        tail[15] = (tail[15].toInt() xor 0x01).toByte()
        val second = Sha256.hash80WithMidstate(midstate, tail)
        assertTrue(!first.contentEquals(second))
    }
}
