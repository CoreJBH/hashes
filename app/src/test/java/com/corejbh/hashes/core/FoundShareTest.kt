package com.corejbh.hashes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FoundShareTest {

    private val genesisHash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

    @Test
    fun writesAHashTheWayABlockExplorerDoes() {
        val digest = Hex.parse(genesisHash).reversedArray()
        val share = FoundShare(nonce = 2083236893L, digest = digest)
        assertEquals(genesisHash, share.hashHex)
        assertArrayEquals(digest, share.hash)
    }

    @Test
    fun computesWorkAgainstTheDifficultyOneTarget() {
        // The genesis hash is about 2536 times less work than the difficulty-1
        // target, which is what the published hash says it must be.
        val digest = Hex.parse(genesisHash).reversedArray()
        val share = FoundShare(0L, digest)
        assertEquals(2536.4262984453103, share.difficulty, 2536.426 * 1e-9)

        // Block 1's hash, for the same arithmetic on a different value.
        val blockOne = Hex.parse(
            "00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048",
        ).reversedArray()
        assertEquals(1.9452038562555845, FoundShare.difficultyOf(blockOne), 1.945 * 1e-9)
    }

    @Test
    fun protectsItsDigestFromCallers() {
        val digest = ByteArray(32) { 7 }
        val share = FoundShare(1L, digest)
        digest[0] = 0
        assertEquals(7, share.hash[0].toInt())
        val copy = share.hash
        copy[0] = 9
        assertEquals(7, share.hash[0].toInt())
    }
}
