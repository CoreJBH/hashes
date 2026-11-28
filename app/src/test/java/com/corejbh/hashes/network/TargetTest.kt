package com.corejbh.hashes.network

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.crypto.Sha256
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTest {

    @Test
    fun `expands the compact target of block 100000`() {
        val expected = BigInteger("000000000004864c000000000000000000000000000000000000000000000000", 16)
        assertEquals(expected, Target.fromCompact(TestVectors.BLOCK_NBITS))
    }

    @Test
    fun `the genesis compact target is difficulty one`() {
        val target = Target.fromCompact("1d00ffff")
        assertEquals(Target.DIFFICULTY_ONE, target)
        assertEquals(1.0, Target.difficulty("1d00ffff").toDouble(), 1e-9)
    }

    @Test
    fun `difficulty of block 100000 is the historical value`() {
        // 14,484.16... was the network difficulty when block 100000 was found.
        assertEquals(14_484.16, Target.difficulty(TestVectors.BLOCK_NBITS).toDouble(), 0.1)
    }

    @Test
    fun `a block hash meets its own target`() {
        val digest = Sha256.doubleHash(Hex.decode(TestVectors.BLOCK_HEADER))
        val target = Target.fromCompact(TestVectors.BLOCK_NBITS)
        assertTrue(Target.meetsTarget(digest, target))
    }

    @Test
    fun `a hash above the target is refused`() {
        val digest = Sha256.doubleHash("not a block".toByteArray())
        val target = Target.fromCompact(TestVectors.BLOCK_NBITS)
        assertFalse(Target.meetsTarget(digest, target))
    }

    @Test
    fun `leading zero digits of the block hash`() {
        val digest = Sha256.doubleHash(Hex.decode(TestVectors.BLOCK_HEADER))
        assertEquals(11, Target.leadingZeroDigits(digest))
    }

    @Test
    fun `target bytes round trip`() {
        val target = Target.fromCompact(TestVectors.BLOCK_NBITS)
        val bytes = Target.toBytes(target)
        assertEquals(32, bytes.size)
        assertEquals(target, Target.fromBytes(bytes))
    }

    @Test
    fun `share difficulty of the block hash is above the network difficulty`() {
        val digest = Sha256.doubleHash(Hex.decode(TestVectors.BLOCK_HEADER))
        val share = Target.hashDifficulty(digest)
        assertTrue(share > Target.difficulty(TestVectors.BLOCK_NBITS))
    }

    @Test
    fun `an all zero digest is the highest possible share`() {
        val difficulty = Target.hashDifficulty(ByteArray(32))
        assertTrue(difficulty > BigDecimal("1e70"))
    }

    @Test
    fun `the difficulty one share target is the difficulty one target`() {
        assertEquals(Target.DIFFICULTY_ONE, Target.fromDifficulty(1.0))
    }

    @Test
    fun `a share target is easier than the network target`() {
        val network = Target.fromCompact(TestVectors.BLOCK_NBITS)
        assertTrue(Target.fromDifficulty(1.0) > network)
        assertTrue(Target.fromDifficulty(0.001) > Target.fromDifficulty(1.0))
    }

    @Test
    fun `a share target divides the difficulty one target by the difficulty`() {
        assertEquals(Target.DIFFICULTY_ONE.shiftRight(1), Target.fromDifficulty(2.0))
        val target = BigDecimal(Target.fromDifficulty(0.001))
        val restored = BigDecimal(Target.DIFFICULTY_ONE).divide(target, MathContext(20)).toDouble()
        assertEquals(0.001, restored, 1e-12)
    }

    @Test
    fun `a difficulty below two to the minus thirty two saturates the target`() {
        assertEquals(Target.MAX_TARGET, Target.fromDifficulty(1e-40))
        assertTrue(Target.toBytes(Target.fromDifficulty(1e-40)).all { it == 0xFF.toByte() })
    }

    @Test
    fun `a difficulty that cannot be divided by is difficulty one`() {
        assertEquals(Target.DIFFICULTY_ONE, Target.fromDifficulty(0.0))
        assertEquals(Target.DIFFICULTY_ONE, Target.fromDifficulty(-2.0))
        assertEquals(Target.DIFFICULTY_ONE, Target.fromDifficulty(Double.NaN))
        assertEquals(Target.DIFFICULTY_ONE, Target.fromDifficulty(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a target a difficulty pushes under one is one`() {
        assertEquals(BigInteger.ONE, Target.fromDifficulty(1e70))
    }

    @Test
    fun `a digest of exactly the share target clears the bar`() {
        val target = Target.fromDifficulty(0.001)
        val exact = Target.toBytes(target).reversedArray()
        assertTrue(Target.meetsTarget(exact, target))
        val above = Target.toBytes(target.add(BigInteger.ONE)).reversedArray()
        assertFalse(Target.meetsTarget(above, target))
    }

    @Test
    fun `expected hashes for a block is difficulty times two to the 32`() {
        val difficulty = Target.difficulty(TestVectors.BLOCK_NBITS)
        val expected = difficulty.toDouble() * 4_294_967_296.0
        assertEquals(expected, Target.expectedHashes(difficulty).toDouble(), expected * 1e-9)
    }

    @Test
    fun `expected time is infinite without a hash rate`() {
        assertTrue(
            Target.expectedSeconds(Target.difficulty(TestVectors.BLOCK_NBITS), 0.0).isInfinite()
        )
    }

    @Test
    fun `expected time uses the hash rate`() {
        val difficulty = Target.difficulty(TestVectors.BLOCK_NBITS)
        val seconds = Target.expectedSeconds(difficulty, 100_000.0)
        val expected = Target.expectedHashes(difficulty).toDouble() / 100_000.0
        assertEquals(expected, seconds, expected * 1e-9)
    }
}
