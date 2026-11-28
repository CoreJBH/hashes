package com.corejbh.hashes.session

import com.corejbh.hashes.TestVectors
import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.engine.FoundShare
import com.corejbh.hashes.engine.HashWork
import com.corejbh.hashes.engine.MiningEngine
import com.corejbh.hashes.network.MiningJob
import com.corejbh.hashes.network.Target
import java.math.BigInteger
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share side of a job, against a recording engine.
 *
 * The bug these lock down: the engine was given `job.networkBits`, the target a
 * block has to beat. A phone never reaches it, so the app hashed for as long as
 * it was left on and the pool never saw a share. A job now goes out with the
 * pool's own difficulty as its target, and a `mining.set_difficulty` that
 * arrives while the job runs is applied without waiting for the next notify.
 */
class SessionJobsTest {

    private fun job(jobId: String = "1") = MiningJob(
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

    /** The engine as the job side sees it: what it was started with, and what was swapped in. */
    private class RecordingEngine : MiningEngine {
        override val name = "recording"

        val started = mutableListOf<HashWork>()
        val updated = Collections.synchronizedList(mutableListOf<HashWork>())
        var stops = 0

        override fun start(work: HashWork) {
            started += work
        }

        override fun updateWork(work: HashWork) {
            updated += work
        }

        override fun stop() {
            stops += 1
        }

        override fun snapshot(): EngineSnapshot = EngineSnapshot.IDLE

        override fun takeFoundShare(): FoundShare? = null

        override fun setTurboMode(enabled: Boolean) = Unit
    }

    private fun open(engine: RecordingEngine) = SessionJobs(engine).apply {
        open()
        onExtranonce(TestVectors.BLOCK_EXTRANONCE1, 4)
    }

    private fun targetOf(work: HashWork): BigInteger = Target.fromBytes(work.target)

    @Test
    fun `a job before the pool sets a difficulty is mined against the network target`() {
        val engine = RecordingEngine()
        val jobs = open(engine)

        val started = jobs.startJob(job())

        assertEquals(1, engine.started.size)
        assertEquals(Target.fromCompact(TestVectors.BLOCK_NBITS), targetOf(engine.started.single()))
        assertEquals(0.0, engine.started.single().difficulty, 0.0)
        assertTrue(started!!.networkDifficulty.toDouble() > 14_000.0)
    }

    @Test
    fun `a job after the pool sets a difficulty is mined against the share target`() {
        val engine = RecordingEngine()
        val jobs = open(engine)
        assertFalse(jobs.onDifficulty(0.001))

        jobs.startJob(job())

        val target = targetOf(engine.started.single())
        assertEquals(Target.fromDifficulty(0.001), target)
        assertTrue(target > Target.fromCompact(TestVectors.BLOCK_NBITS))
        assertEquals(0.001, engine.started.single().difficulty, 0.0)
    }

    @Test
    fun `a difficulty that arrives mid-job retargets the work already running`() {
        val engine = RecordingEngine()
        val jobs = open(engine)
        jobs.startJob(job())

        assertTrue(jobs.onDifficulty(0.001))

        val running = engine.started.single()
        val retargeted = engine.updated.single()
        assertEquals(Target.fromDifficulty(0.001), targetOf(retargeted))
        assertEquals(0.001, retargeted.difficulty, 0.0)
        // Same header and same extranonce2: only the bar the share has to clear moved.
        assertTrue(running.headerPrefix.contentEquals(retargeted.headerPrefix))
        assertEquals(running.extranonce2, retargeted.extranonce2)
        assertEquals(running.jobId, retargeted.jobId)
    }

    @Test
    fun `a difficulty after the link dropped does not reach the engine`() {
        val engine = RecordingEngine()
        val jobs = open(engine)
        jobs.startJob(job())
        jobs.stopEngine()

        assertFalse(jobs.onDifficulty(0.001))
        assertEquals(1, engine.stops)
        assertTrue(engine.updated.isEmpty())

        // The next job starts the engine again, with the difficulty just learned.
        jobs.startJob(job("2"))
        assertEquals(2, engine.started.size)
        assertEquals(Target.fromDifficulty(0.001), targetOf(engine.started.last()))
    }

    @Test
    fun `two jobs never share an extranonce2`() {
        val engine = RecordingEngine()
        val jobs = open(engine)

        jobs.startJob(job("1"))
        jobs.startJob(job("2"))

        assertEquals(1, engine.started.size)
        assertEquals(1, engine.updated.size)
        assertNotEquals(engine.started.single().extranonce2, engine.updated.single().extranonce2)
    }

    @Test
    fun `a closed session starts nothing`() {
        val engine = RecordingEngine()
        val jobs = open(engine)
        jobs.close()

        assertNull(jobs.startJob(job()))
        assertTrue(engine.started.isEmpty())
    }

    @Test
    fun `a job waits for the pool session`() {
        val engine = RecordingEngine()
        val jobs = SessionJobs(engine)
        jobs.open()

        assertNull(jobs.startJob(job()))
        assertTrue(engine.started.isEmpty())
    }

    @Test
    fun `job starts from many threads pick a distinct extranonce2 for each`() {
        val engine = RecordingEngine()
        val jobs = open(engine)
        val threads = (0 until 4).map { worker ->
            Thread {
                repeat(25) { index -> jobs.startJob(job("$worker-$index")) }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        val chosen = (engine.started + engine.updated.toList()).map { it.extranonce2 }
        assertEquals(100, chosen.size)
        assertEquals(100, chosen.toSet().size)
        assertEquals(1, engine.started.size)
    }
}
