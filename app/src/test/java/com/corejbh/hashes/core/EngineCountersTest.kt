package com.corejbh.hashes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCountersTest {

    private fun counters(
        state: Long = 1,
        hashes: Long = 1_000_000,
        nonce: Long = 0x1DAC2B7C,
        threads: Long = 4,
        rate: Long = 2_500_000,
        nibbles: Long = 6,
        flags: Long = 1,
    ): LongArray = longArrayOf(state, hashes, nonce, threads, rate, nibbles, flags, 0)

    @Test
    fun readsEverySlotInTheNativeOrder() {
        val read = EngineCounters.from(counters())

        assertEquals(EngineState.RUNNING, read.state)
        assertEquals(1_000_000L, read.hashes)
        assertEquals(0x1DAC2B7CL, read.nonce)
        assertEquals(4, read.threads)
        assertEquals(2_500_000L, read.hashRate)
        assertEquals(6, read.bestLeadingZeroNibbles)
        assertTrue(read.hardwareSha256)
        assertTrue(read.running)
    }

    @Test
    fun theFlagBitIsTheOnlyThingThatMeansHardware() {
        assertFalse(EngineCounters.from(counters(flags = 0)).hardwareSha256)
        assertTrue(EngineCounters.from(counters(flags = 1)).hardwareSha256)
        assertTrue(EngineCounters.from(counters(flags = 0b101)).hardwareSha256)
    }

    @Test
    fun everyStateCodeReadsBackAndUnknownCodesAreIdle() {
        assertEquals(EngineState.IDLE, EngineCounters.from(counters(state = 0)).state)
        assertEquals(EngineState.RUNNING, EngineCounters.from(counters(state = 1)).state)
        assertEquals(EngineState.STOPPING, EngineCounters.from(counters(state = 2)).state)
        assertEquals(EngineState.ERROR, EngineCounters.from(counters(state = 3)).state)
        assertEquals(EngineState.IDLE, EngineCounters.from(counters(state = 99)).state)
        assertFalse(EngineCounters.from(counters(state = 0)).running)
    }

    @Test
    fun theIndexesMatchTheSlotsTheBridgeFills() {
        assertEquals(8, NativeMinerSlots.SLOTS)
        assertEquals(EngineCounters.INDEX_STATE, 0)
        assertEquals(EngineCounters.INDEX_HASHES, 1)
        assertEquals(EngineCounters.INDEX_NONCE, 2)
        assertEquals(EngineCounters.INDEX_THREADS, 3)
        assertEquals(EngineCounters.INDEX_HASH_RATE, 4)
        assertEquals(EngineCounters.INDEX_BEST_ZERO_NIBBLES, 5)
        assertEquals(EngineCounters.INDEX_FLAGS, 6)
        assertEquals(EngineCounters.INDEX_RESERVED, 7)
    }

    /** Mirrors NativeMiner.COUNTER_SLOTS without touching the JNI object in a
     * JVM test, where loading the library is not possible. */
    private object NativeMinerSlots {
        const val SLOTS = 8
    }
}
