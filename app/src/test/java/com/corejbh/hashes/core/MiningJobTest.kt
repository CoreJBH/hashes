package com.corejbh.hashes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningJobTest {

    private fun prefix(): ByteArray = SelfTestJob.blockZero().headerPrefix

    @Test
    fun theSelfTestJobIsBlockZeroWithoutItsNonce() {
        val job = SelfTestJob.blockZero()
        assertEquals(MiningJob.PREFIX_SIZE, job.headerPrefix.size)
        assertEquals(MiningJob.TARGET_SIZE, job.target.size)
        assertEquals(0L, job.nonceStart)

        // The header's last four bytes are the nonce and are not part of the
        // prefix; the four bytes before them are nbits.
        assertEquals(SelfTestJob.difficultyOneBits(), job.bits)
        // nbits 0x1d00ffff is little-endian on the wire: ff ff 00 1d.
        assertEquals(0x1DL, job.headerPrefix[MiningJob.PREFIX_SIZE - 1].toLong() and 0xFF)
    }

    @Test
    fun theSelfTestTargetIsDifficultyOne() {
        val target = SelfTestJob.difficultyOneTarget()
        assertEquals(0, target[0].toInt())
        assertEquals(0, target[1].toInt())
        assertEquals(0, target[2].toInt())
        assertEquals(0, target[3].toInt())
        assertEquals(0xFF, target[4].toInt() and 0xFF)
        assertEquals(0xFF, target[5].toInt() and 0xFF)
        assertEquals(0, target[31].toInt())
    }

    @Test
    fun rejectsAPrefixOfTheWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            MiningJob(ByteArray(80), SelfTestJob.difficultyOneTarget())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MiningJob(prefix(), ByteArray(31))
        }
    }

    @Test
    fun rejectsNoncesOutsideTheThirtyTwoBitRange() {
        assertThrows(IllegalArgumentException::class.java) {
            MiningJob(prefix(), SelfTestJob.difficultyOneTarget(), nonceStart = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MiningJob(prefix(), SelfTestJob.difficultyOneTarget(), nonceStart = 0x1_0000_0000L)
        }
    }

    @Test
    fun acceptsTheWholeNonceRange() {
        val job = MiningJob(prefix(), SelfTestJob.difficultyOneTarget(), nonceStart = MiningJob.MAX_NONCE)
        assertEquals(MiningJob.MAX_NONCE, job.nonceStart)
        assertFalse(job.headerPrefix.isEmpty())
        assertTrue(job.bits and 0xFFFFFFL == 0x00FFFFL)
    }
}
