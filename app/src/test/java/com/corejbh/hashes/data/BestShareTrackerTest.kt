package com.corejbh.hashes.data

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BestShareTrackerTest {

    private class MemoryPersistence(var stored: BestShareRecord? = null) : BestSharePersistence {
        var writes = 0
        override fun read(): BestShareRecord? = stored
        override fun write(record: BestShareRecord) {
            stored = record
            writes += 1
        }
    }

    /** A digest whose displayed hash is 57 zero nibbles followed by 01 - a very valuable share. */
    private fun strongHash(): ByteArray = ByteArray(32).also { bytes ->
        bytes[3] = 0x01
    }

    /** An ordinary digest with no leading zeros. */
    private fun weakHash(): ByteArray = ByteArray(32) { 0xFF.toByte() }

    @Test
    fun `a strong hash becomes both records`() {
        val persistence = MemoryPersistence()
        val tracker = BestShareTracker(persistence, clock = { 1_000L })
        assertEquals(BestShareTracker.Update.ALL_TIME, tracker.observe(strongHash()))
        assertNotNull(tracker.sessionBest())
        assertNotNull(tracker.allTimeBest())
        assertEquals(1, persistence.writes)
        assertEquals(1_000L, tracker.allTimeBest()!!.timestampMillis)
    }

    @Test
    fun `a weaker hash does not replace the record`() {
        val persistence = MemoryPersistence()
        val tracker = BestShareTracker(persistence)
        tracker.observe(strongHash())
        assertEquals(BestShareTracker.Update.NONE, tracker.observe(weakHash()))
        assertEquals(1, persistence.writes)
    }

    @Test
    fun `the all time record survives a restart`() {
        val persistence = MemoryPersistence()
        val first = BestShareTracker(persistence)
        first.observe(strongHash())
        val second = BestShareTracker(persistence)
        assertNotNull(second.allTimeBest())
        assertEquals(first.allTimeBest()!!.hashHex, second.allTimeBest()!!.hashHex)
        assertNull(second.sessionBest())
    }

    @Test
    fun `reset session keeps the all time record`() {
        val tracker = BestShareTracker(MemoryPersistence())
        tracker.observe(strongHash())
        tracker.resetSession()
        assertNull(tracker.sessionBest())
        assertNotNull(tracker.allTimeBest())
    }

    @Test
    fun `a hash of the wrong size is ignored`() {
        val tracker = BestShareTracker(MemoryPersistence())
        assertEquals(BestShareTracker.Update.NONE, tracker.observe(ByteArray(16)))
    }

    @Test
    fun `observe hex reads display order`() {
        val tracker = BestShareTracker(MemoryPersistence())
        // Display order hex of the strong digest: reversed bytes.
        val display = com.corejbh.hashes.crypto.Hex.encode(strongHash().reversedArray())
        assertEquals(BestShareTracker.Update.ALL_TIME, tracker.observeHex(display))
        assertEquals(display, tracker.allTimeBest()!!.hashHex)
    }

    @Test
    fun `the record keeps its difficulty and leading zero count`() {
        val tracker = BestShareTracker(MemoryPersistence())
        tracker.observe(strongHash())
        val record = tracker.allTimeBest()!!
        assertTrue(record.difficulty > BigDecimal("1e50"))
        assertEquals(57, record.leadingZeros)
    }
}
