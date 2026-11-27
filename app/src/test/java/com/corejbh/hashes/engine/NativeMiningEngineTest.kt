package com.corejbh.hashes.engine

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMiningEngineTest {

    private class FakeBinding : NativeMinerBinding {
        override var available = true
        var startedPrefix: ByteArray? = null
        var startedTarget: ByteArray? = null
        var startedNonce: Long = 0
        var turbo = false
        var dutyHash = -1
        var dutySleep = -1
        var updated = false
        var stopped = false
        val threadCaps = mutableListOf<Int>()
        var foundNonce: Long = -1
        var bestPresent = false
        val workerHashes = longArrayOf(11, 22, 33, 44)

        val counters = LongArray(NativeMinerBinding.Counter.SIZE)
        val hash = ByteArray(32) { 0x11 }
        val best = ByteArray(32) { 0x22 }
        val found = ByteArray(32) { 0x33 }

        override fun start(
            headerPrefix: ByteArray,
            target: ByteArray,
            nonceStart: Long,
            turbo: Boolean,
            dutyHashMicros: Int,
            dutySleepMicros: Int,
        ): Boolean {
            startedPrefix = headerPrefix
            startedTarget = target
            startedNonce = nonceStart
            this.turbo = turbo
            dutyHash = dutyHashMicros
            dutySleep = dutySleepMicros
            counters[NativeMinerBinding.Counter.STATE] = NativeMinerBinding.Counter.STATE_RUNNING
            return true
        }

        override fun updateWork(headerPrefix: ByteArray, target: ByteArray, nonceStart: Long): Boolean {
            updated = true
            startedPrefix = headerPrefix
            return true
        }

        override fun setTurboMode(turbo: Boolean) {
            this.turbo = turbo
        }

        override fun setThreadCap(threads: Int) {
            threadCaps += threads
        }

        override fun stop() {
            stopped = true
            counters[NativeMinerBinding.Counter.STATE] = NativeMinerBinding.Counter.STATE_IDLE
        }

        override fun pollCounters(out: LongArray) {
            System.arraycopy(counters, 0, out, 0, counters.size)
        }

        override fun pollThreadHashes(out: LongArray): Int {
            System.arraycopy(workerHashes, 0, out, 0, workerHashes.size)
            return workerHashes.size
        }

        override fun pollCurrentHash(out: ByteArray) {
            System.arraycopy(hash, 0, out, 0, 32)
        }

        override fun pollBestHash(out: ByteArray): Boolean {
            if (bestPresent) System.arraycopy(best, 0, out, 0, 32)
            return bestPresent
        }

        override fun pollFoundHash(out: ByteArray): Long {
            if (foundNonce < 0) return -1
            System.arraycopy(found, 0, out, 0, 32)
            val nonce = foundNonce
            foundNonce = -1
            return nonce
        }

        override fun version(): String = "fake-1"
    }

    private fun work() = HashWork(
        jobId = "bf",
        headerPrefix = ByteArray(76) { 0x0a },
        target = ByteArray(32) { 0x0f },
        nonceStart = 42,
    )

    @Test
    fun `starts the core with the cool duty cycle`() {
        val binding = FakeBinding()
        val engine = NativeMiningEngine(binding)
        engine.start(work())
        assertEquals(1_500, binding.dutyHash)
        assertEquals(31_000, binding.dutySleep)
        assertFalse(binding.turbo)
        assertEquals(42L, binding.startedNonce)
        assertTrue(engine.isAvailable())
        assertEquals(true, engine.snapshot().running)
    }

    @Test
    fun `turbo changes the duty cycle on the next start`() {
        val binding = FakeBinding()
        val engine = NativeMiningEngine(binding)
        engine.setTurboMode(true)
        assertTrue(binding.turbo)
        engine.start(work())
        assertEquals(0, binding.dutyHash)
        assertEquals(0, binding.dutySleep)
    }

    @Test
    fun `snapshot maps every counter`() {
        val binding = FakeBinding()
        binding.counters[NativeMinerBinding.Counter.STATE] = NativeMinerBinding.Counter.STATE_RUNNING
        binding.counters[NativeMinerBinding.Counter.HASHES] = 1_234_567
        binding.counters[NativeMinerBinding.Counter.NONCE] = 77
        binding.counters[NativeMinerBinding.Counter.THREADS] = 4
        binding.counters[NativeMinerBinding.Counter.HASH_RATE] = 250_000
        binding.counters[NativeMinerBinding.Counter.HARDWARE_FLAG] = 1
        val engine = NativeMiningEngine(binding)
        val snapshot = engine.snapshot()
        assertTrue(snapshot.running)
        assertEquals(1_234_567L, snapshot.hashesTried)
        assertEquals(77L, snapshot.nonce)
        assertEquals(4, snapshot.threads)
        assertEquals(250_000.0, snapshot.hashRate, 0.001)
        assertTrue(snapshot.hardwareSha256)
        assertNull(snapshot.bestHash)
        assertEquals("native", snapshot.engineName)
    }

    @Test
    fun `per worker totals are copied into the snapshot`() {
        val binding = FakeBinding()
        binding.counters[NativeMinerBinding.Counter.STATE] = NativeMinerBinding.Counter.STATE_RUNNING
        binding.counters[NativeMinerBinding.Counter.THREADS] = 4
        val engine = NativeMiningEngine(binding)
        val snapshot = engine.snapshot()
        assertEquals(listOf(11L, 22L, 33L, 44L), snapshot.workerHashes.toList())
    }

    @Test
    fun `best hash is copied when the core reports one`() {
        val binding = FakeBinding()
        binding.bestPresent = true
        val engine = NativeMiningEngine(binding)
        val best = engine.snapshot().bestHash
        assertNotNull(best)
        assertEquals(0x22.toByte(), best!![0])
    }

    @Test
    fun `found share is drained once`() {
        val binding = FakeBinding()
        binding.foundNonce = 0x10572b0fL
        val engine = NativeMiningEngine(binding)
        engine.start(work())
        val share = engine.takeFoundShare()
        assertNotNull(share)
        assertEquals(0x10572b0fL, share!!.nonce)
        assertEquals("bf", share.work.jobId)
        assertNull(engine.takeFoundShare())
    }

    @Test
    fun `no share is invented while the core has none`() {
        val binding = FakeBinding()
        val engine = NativeMiningEngine(binding)
        engine.start(work())
        assertNull(engine.takeFoundShare())
    }

    @Test
    fun `update work is forwarded`() {
        val binding = FakeBinding()
        val engine = NativeMiningEngine(binding)
        engine.start(work())
        engine.updateWork(work())
        assertTrue(binding.updated)
    }

    @Test
    fun `an unavailable binding is reported and never started`() {
        val binding = FakeBinding()
        binding.available = false
        val engine = NativeMiningEngine(binding)
        assertFalse(engine.isAvailable())
        assertEquals("fake-1", engine.version())
    }

    @Test
    fun `stop is forwarded`() {
        val binding = FakeBinding()
        val engine = NativeMiningEngine(binding)
        engine.stop()
        assertTrue(binding.stopped)
        assertFalse(engine.snapshot().running)
    }

    /**
     * A binding that numbers every reading and reports how many calls were inside
     * it at once.
     *
     * `pollCounters` starts a new generation and `pollCurrentHash`/`pollThreadHashes`
     * copy that generation into their buffers, so a snapshot whose fields do not
     * carry the same generation was assembled from two overlapping polls. The
     * widening loop makes an overlap long enough to happen, not merely possible.
     */
    private class PollProbeBinding : NativeMinerBinding {
        override val available = true

        private val inside = AtomicInteger(0)
        private val mostInside = AtomicInteger(0)
        private var generation = 0L

        /** The largest number of calls that were ever in the binding together. */
        val maxConcurrentCalls: Int get() = mostInside.get()

        private fun enter() {
            val now = inside.incrementAndGet()
            mostInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        }

        private fun leave() = inside.decrementAndGet()

        private fun widen() {
            var spin = 0
            while (spin < 400) {
                Thread.onSpinWait()
                spin += 1
            }
        }

        override fun start(
            headerPrefix: ByteArray,
            target: ByteArray,
            nonceStart: Long,
            turbo: Boolean,
            dutyHashMicros: Int,
            dutySleepMicros: Int,
        ): Boolean = guarded {
            generation = 1
            true
        }

        override fun updateWork(headerPrefix: ByteArray, target: ByteArray, nonceStart: Long): Boolean =
            guarded { true }

        override fun setTurboMode(turbo: Boolean) = guarded { Unit }

        override fun setThreadCap(threads: Int) = guarded { Unit }

        override fun stop() = guarded { Unit }

        override fun pollCounters(out: LongArray) = guarded {
            generation += 1
            widen()
            out.fill(0)
            out[NativeMinerBinding.Counter.STATE] = NativeMinerBinding.Counter.STATE_RUNNING
            out[NativeMinerBinding.Counter.HASHES] = generation
            out[NativeMinerBinding.Counter.THREADS] = NativeMinerBinding.MAX_THREADS.toLong()
        }

        override fun pollThreadHashes(out: LongArray): Int = guarded {
            widen()
            out.fill(generation)
            NativeMinerBinding.MAX_THREADS
        }

        override fun pollCurrentHash(out: ByteArray) = guarded {
            widen()
            out.fill((generation % 128).toByte())
        }

        override fun pollBestHash(out: ByteArray): Boolean = guarded { false }

        override fun pollFoundHash(out: ByteArray): Long = guarded { -1 }

        override fun version(): String = "probe-1"

        private inline fun <T> guarded(block: () -> T): T {
            enter()
            try {
                return block()
            } finally {
                leave()
            }
        }
    }

    private fun isOneReading(snapshot: EngineSnapshot): Boolean {
        val expected = (snapshot.hashesTried % 128).toByte()
        return snapshot.hashesTried > 0 &&
            snapshot.currentHash.all { it == expected } &&
            snapshot.workerHashes.isNotEmpty() &&
            snapshot.workerHashes.all { it == snapshot.hashesTried }
    }

    @Test
    fun `snapshots taken from several threads are one reading each`() {
        val binding = PollProbeBinding()
        val engine = NativeMiningEngine(binding)
        engine.start(work())

        val torn = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val threads = (0 until THREADS).map {
            Thread {
                try {
                    repeat(SNAPSHOTS_PER_THREAD) {
                        if (!isOneReading(engine.snapshot())) torn.incrementAndGet()
                    }
                } catch (error: RuntimeException) {
                    failures.incrementAndGet()
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(0, failures.get())
        assertEquals(0, torn.get())
        assertEquals(1, binding.maxConcurrentCalls)
    }

    @Test
    fun `a frame poll and a session poll cannot meet inside the core while a job changes`() {
        val binding = PollProbeBinding()
        val engine = NativeMiningEngine(binding)
        engine.start(work())

        val torn = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val writer = Thread {
            try {
                repeat(JOB_CHANGES) { round ->
                    engine.updateWork(work())
                    engine.setTurboMode(round % 2 == 0)
                    engine.start(work())
                    engine.stop()
                }
            } catch (error: RuntimeException) {
                failures.incrementAndGet()
            }
        }
        val readers = (0 until THREADS).map {
            Thread {
                try {
                    repeat(SNAPSHOTS_PER_THREAD) {
                        if (!isOneReading(engine.snapshot())) torn.incrementAndGet()
                    }
                } catch (error: RuntimeException) {
                    failures.incrementAndGet()
                }
            }
        }
        (readers + writer).forEach(Thread::start)
        (readers + writer).forEach(Thread::join)

        assertEquals(0, failures.get())
        assertEquals(0, torn.get())
        assertEquals(1, binding.maxConcurrentCalls)
    }

    private companion object {
        const val THREADS = 4
        const val SNAPSHOTS_PER_THREAD = 400
        const val JOB_CHANGES = 200
    }
}
