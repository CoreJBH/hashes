package com.corejbh.hashes.network

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.crypto.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockAssemblerTest {

    private fun job() = MiningJob(
        jobId = "bf",
        previousHash = TestVectors.BLOCK_PREV_HASH_NOTIFY,
        coinbase1 = TestVectors.BLOCK_COINB1,
        coinbase2 = TestVectors.BLOCK_COINB2,
        merkleBranches = listOf(TestVectors.BLOCK_BRANCH_0, TestVectors.BLOCK_BRANCH_1),
        version = TestVectors.BLOCK_VERSION,
        networkBits = TestVectors.BLOCK_NBITS,
        networkTime = TestVectors.BLOCK_NTIME,
        cleanJobs = true,
    )

    @Test
    fun `coinbase parts reassemble the real coinbase transaction`() {
        val coinbase = BlockAssembler.coinbase(
            coinbase1 = TestVectors.BLOCK_COINB1,
            extranonce1 = TestVectors.BLOCK_EXTRANONCE1,
            extranonce2 = TestVectors.BLOCK_EXTRANONCE2,
            coinbase2 = TestVectors.BLOCK_COINB2,
        )
        assertEquals(TestVectors.BLOCK_COINBASE, Hex.encode(coinbase))
    }

    @Test
    fun `coinbase txid matches the chain`() {
        val coinbase = Hex.decode(TestVectors.BLOCK_COINBASE)
        val txid = BlockAssembler.coinbaseTxId(coinbase)
        assertEquals(TestVectors.BLOCK_COINBASE_TXID, Hex.encode(txid.reversedArray()))
    }

    @Test
    fun `merkle root matches the header field of the real block`() {
        val coinbase = Hex.decode(TestVectors.BLOCK_COINBASE)
        val root = BlockAssembler.merkleRoot(
            BlockAssembler.coinbaseTxId(coinbase),
            listOf(TestVectors.BLOCK_BRANCH_0, TestVectors.BLOCK_BRANCH_1),
        )
        assertEquals(TestVectors.BLOCK_MERKLE_ROOT, Hex.encode(root))
    }

    @Test
    fun `the assembled header is the real block header`() {
        val prefix = BlockAssembler.headerPrefix(
            job = job(),
            extranonce1 = TestVectors.BLOCK_EXTRANONCE1,
            extranonce2 = TestVectors.BLOCK_EXTRANONCE2,
        )
        val header = BlockAssembler.header(prefix, TestVectors.BLOCK_NONCE)
        assertEquals(TestVectors.BLOCK_HEADER, Hex.encode(header))
    }

    @Test
    fun `the assembled header hashes to the real block hash`() {
        val prefix = BlockAssembler.headerPrefix(
            job = job(),
            extranonce1 = TestVectors.BLOCK_EXTRANONCE1,
            extranonce2 = TestVectors.BLOCK_EXTRANONCE2,
        )
        val digest = BlockAssembler.hashHeader(BlockAssembler.header(prefix, TestVectors.BLOCK_NONCE))
        assertEquals(TestVectors.BLOCK_HASH, Hex.encode(digest.reversedArray()))
    }

    @Test
    fun `the notify previous hash word swap becomes the header field`() {
        assertEquals(
            TestVectors.BLOCK_PREV_HASH_FIELD,
            Hex.swapWords32(TestVectors.BLOCK_PREV_HASH_NOTIFY),
        )
    }

    @Test
    fun `the word swap is its own inverse`() {
        val once = Hex.swapWords32(TestVectors.BLOCK_PREV_HASH_NOTIFY)
        assertEquals(TestVectors.BLOCK_PREV_HASH_NOTIFY, Hex.swapWords32(once))
    }

    @Test
    fun `the nonce is written little endian`() {
        val header = BlockAssembler.header(ByteArray(76), 0x10572b0fL)
        assertEquals("0f2b5710", Hex.encode(header.copyOfRange(76, 80)))
    }

    @Test
    fun `extranonce2 is padded to the announced size`() {
        assertEquals("00000001", BlockAssembler.extranonce2Hex(1, 4))
        assertEquals("deadbeef", BlockAssembler.extranonce2Hex(0xdeadbeefL, 4))
        assertEquals("0102", BlockAssembler.extranonce2Hex(0x0102, 2))
    }

    @Test
    fun `a prefix of the wrong size is refused`() {
        var failed = false
        try {
            BlockAssembler.header(ByteArray(75), 0)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertEquals(true, failed)
    }

    @Test
    fun `hashing a header of the wrong size is refused`() {
        var failed = false
        try {
            BlockAssembler.hashHeader(ByteArray(79))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertEquals(true, failed)
    }

    @Test
    fun `different extranonce2 values change the coinbase`() {
        val first = BlockAssembler.coinbase(
            TestVectors.BLOCK_COINB1, TestVectors.BLOCK_EXTRANONCE1, "00000001", TestVectors.BLOCK_COINB2,
        )
        val second = BlockAssembler.coinbase(
            TestVectors.BLOCK_COINB1, TestVectors.BLOCK_EXTRANONCE1, "00000002", TestVectors.BLOCK_COINB2,
        )
        assertArrayEquals(first, first)
        assertEquals(false, first.contentEquals(second))
    }
}
