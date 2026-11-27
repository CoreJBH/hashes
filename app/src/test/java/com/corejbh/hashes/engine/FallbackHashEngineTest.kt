package com.corejbh.hashes.engine

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.crypto.Sha256
import com.corejbh.hashes.network.BlockAssembler
import com.corejbh.hashes.network.MiningJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackHashEngineTest {

    private fun job(jobId: String = "bf") = MiningJob(
        jobId = jobId,
        previousHash = TestVectors.BLOCK_PREV_HASH_NOTIFY,
        coinbase1 = TestVectors.BLOCK_COINB1,
        coinbase2 = TestVectors.BLOCK_COINB2,
        merkleBranches = listOf(TestVectors.BLOCK_BRANCH_0, TestVectors.BLOCK_BRANCH_1),
        version = TestVectors.BLOCK_VERSION,
        networkBits = TestVectors.BLOCK_NBITS,
        networkTime = TestVectors.BLOCK_NTIME,
        cleanJobs = true,
    )

    private fun work(target: ByteArray, prefix: ByteArray = prefix()): HashWork = HashWork(
        jobId = "bf",
        headerPrefix = prefix,
        target = target,
        nonceStart = 0,
    )

    private fun prefix(): ByteArray = BlockAssembler.headerPrefix(
        job = job(),
        extranonce1 = TestVectors.BLOCK_EXTRANONCE1,
        extranonce2 = TestVectors.BLOCK_EXTRANONCE2,
    )

    private fun awaitHashes(engine: MiningEngine, minimum: Long, timeoutMs: Long = 8_000): EngineSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot = engine.snapshot()
        while (snapshot.hashesTried < minimum && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            snapshot = engine.snapshot()
        }
        return snapshot
    }

    @Test
    fun `hashes real headers and stops cleanly`() {
        val engine = FallbackHashEngine()
        engine.start(work(ByteArray(32) { 0xFF.toByte() }))
        val snapshot = awaitHashes(engine, 500)
        assertTrue("engine should have hashed", snapshot.hashesTried >= 500)
        assertTrue(snapshot.running)
        assertEquals(1, snapshot.threads)
        assertEquals("kotlin-fallback", snapshot.engineName)
        engine.stop()
        val stopped = engine.snapshot()
        assertFalse(stopped.running)
        val afterStop = stopped.hashesTried
        Thread.sleep(150)
        assertEquals(afterStop, engine.snapshot().hashesTried)
        engine.close()
    }

    @Test
    fun `a found share carries a hash that really meets the target`() {
        val engine = FallbackHashEngine()
        val prefix = prefix()
        engine.start(work(ByteArray(32) { 0xFF.toByte() }, prefix))
        val deadline = System.currentTimeMillis() + 8_000
        var found: FoundShare? = null
        while (found == null && System.currentTimeMillis() < deadline) {
            found = engine.takeFoundShare()
            Thread.sleep(10)
        }
        engine.stop()
        assertNotNull("every hash meets an all-ones target", found)
        val share = found!!
        val header = BlockAssembler.header(prefix, share.nonce)
        val digest = Sha256.doubleHash(header)
        assertEquals(Hex.encode(digest), Hex.encode(share.hash))
        engine.close()
    }

    @Test
    fun `an unreachable target produces no share and no best hash`() {
        val engine = FallbackHashEngine()
        engine.start(work(ByteArray(32)))
        val snapshot = awaitHashes(engine, 300)
        engine.stop()
        assertNull(engine.takeFoundShare())
        assertNull(snapshot.bestHash)
        assertTrue(snapshot.hashesTried >= 300)
        engine.close()
    }

    @Test
    fun `turbo mode is reported back and keeps hashing`() {
        val engine = FallbackHashEngine()
        engine.setTurboMode(true)
        engine.start(work(ByteArray(32)))
        val snapshot = awaitHashes(engine, 200)
        assertTrue(snapshot.turbo)
        assertTrue(snapshot.hashesTried >= 200)
        engine.stop()
        engine.close()
    }

    @Test
    fun `update work keeps the engine running`() {
        val engine = FallbackHashEngine()
        engine.start(work(ByteArray(32)))
        awaitHashes(engine, 100)
        engine.updateWork(work(ByteArray(32)))
        val snapshot = awaitHashes(engine, 200)
        assertTrue(snapshot.running)
        engine.close()
    }
}
