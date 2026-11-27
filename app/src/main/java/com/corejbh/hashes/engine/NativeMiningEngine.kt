package com.corejbh.hashes.engine

import kotlin.math.max

/**
 * Adapts [NativeMinerBinding] to the [MiningEngine] contract the UI polls.
 *
 * Everything here is a translation: the counters the native core publishes become
 * an [EngineSnapshot], and a target-meeting hash the core queued becomes a
 * [FoundShare] carrying the work it belongs to. The engine never talks to the
 * network, and nothing is invented when the core reports no found share.
 *
 * ### One reading at a time
 *
 * Two loops poll this engine at once: the screen's 30 fps frame loop on `Main`
 * and the session's one-second housekeeping loop on `Dispatchers.Default`. Every
 * poll fills a buffer owned by this class ([counters], [workerCounters],
 * [hashBuffer], [bestBuffer], [foundBuffer]) and the rate smoother keeps
 * `lastSample*`/`smoothedRate` across calls, so two polls that overlap would
 * read each other's half-written numbers — a snapshot mixing one counters read
 * with another's digest, or a rate computed from a sample it never took.
 *
 * [lock] is therefore held for every method that touches those buffers or calls
 * into the core, which is what makes one [snapshot] a single consistent reading.
 * The critical sections are a handful of JNI calls each and never call back into
 * Kotlin, so the second caller waits microseconds rather than deadlocking.
 * A [stop] can wait for the native join, which is the longest section here and
 * still bounded by the core's own stop.
 */
class NativeMiningEngine(
    private val binding: NativeMinerBinding = JniMinerBinding(),
) : MiningEngine {

    override val name: String = "native"

    /** Guards every shared buffer and every call into the core; see the class note. */
    private val lock = Any()

    @Volatile
    private var work: HashWork? = null

    @Volatile
    private var turbo = false

    private val counters = LongArray(NativeMinerBinding.Counter.SIZE)
    private val workerCounters = LongArray(NativeMinerBinding.MAX_THREADS)
    private val hashBuffer = ByteArray(32)
    private val bestBuffer = ByteArray(32)
    private val foundBuffer = ByteArray(32)

    private var lastSampleNanos = 0L
    private var lastSampleHashes = 0L
    private var smoothedRate = 0.0

    fun isAvailable(): Boolean = binding.available

    fun version(): String = binding.version()

    override fun start(work: HashWork) {
        synchronized(lock) {
            this.work = work
            lastSampleNanos = System.nanoTime()
            lastSampleHashes = 0
            smoothedRate = 0.0
            binding.start(
                headerPrefix = work.headerPrefix,
                target = work.target,
                nonceStart = work.nonceStart,
                turbo = turbo,
                dutyHashMicros = DutyCycle.hashMicros(turbo),
                dutySleepMicros = DutyCycle.sleepMicros(turbo),
            )
        }
    }

    override fun updateWork(work: HashWork) {
        synchronized(lock) {
            this.work = work
            binding.updateWork(work.headerPrefix, work.target, work.nonceStart)
        }
    }

    override fun setTurboMode(enabled: Boolean) {
        synchronized(lock) {
            turbo = enabled
            binding.setTurboMode(enabled)
        }
    }

    /** The MODERATE thermal step: TURBO keeps running on half the workers. */
    override fun setThreadCap(threads: Int) {
        synchronized(lock) {
            binding.setThreadCap(threads)
        }
    }

    override fun stop() {
        synchronized(lock) {
            binding.stop()
        }
    }

    override fun snapshot(): EngineSnapshot = synchronized(lock) {
        binding.pollCounters(counters)
        binding.pollCurrentHash(hashBuffer)
        val hasBest = binding.pollBestHash(bestBuffer)
        val hashes = counters[NativeMinerBinding.Counter.HASHES]
        val reportedRate = counters[NativeMinerBinding.Counter.HASH_RATE].toDouble()
        val now = System.nanoTime()
        val rate = if (reportedRate > 0.0) {
            reportedRate
        } else if (lastSampleNanos > 0 && now > lastSampleNanos) {
            val deltaHashes = max(0L, hashes - lastSampleHashes)
            val deltaSeconds = (now - lastSampleNanos) / 1_000_000_000.0
            val instant = deltaHashes / deltaSeconds
            smoothedRate = if (smoothedRate == 0.0) instant else smoothedRate * 0.7 + instant * 0.3
            lastSampleHashes = hashes
            lastSampleNanos = now
            smoothedRate
        } else {
            0.0
        }
        val running = counters[NativeMinerBinding.Counter.STATE] ==
            NativeMinerBinding.Counter.STATE_RUNNING
        val workers = binding.pollThreadHashes(workerCounters)
        val workerHashes = if (workers > 0) {
            workerCounters.copyOfRange(0, minOf(workers, workerCounters.size))
        } else {
            LongArray(0)
        }
        EngineSnapshot(
            running = running,
            turbo = turbo,
            hashesTried = hashes,
            hashRate = rate,
            nonce = counters[NativeMinerBinding.Counter.NONCE],
            currentHash = hashBuffer.copyOf(),
            threads = counters[NativeMinerBinding.Counter.THREADS].toInt(),
            bestHash = if (hasBest) bestBuffer.copyOf() else null,
            hardwareSha256 = (counters[NativeMinerBinding.Counter.HARDWARE_FLAG] and 1L) == 1L,
            engineName = name,
            workerHashes = workerHashes,
        )
    }

    override fun takeFoundShare(): FoundShare? = synchronized(lock) {
        val currentWork = work
        if (currentWork == null) {
            null
        } else {
            val nonce = binding.pollFoundHash(foundBuffer)
            if (nonce < 0) {
                null
            } else {
                FoundShare(
                    work = currentWork,
                    nonce = nonce and 0xffffffffL,
                    hash = foundBuffer.copyOf(),
                )
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            binding.stop()
            work = null
        }
    }

    /** COOL is a 1.5 ms hash window in a 33 ms frame; TURBO leaves pacing to the core. */
    object DutyCycle {
        const val COOL_HASH_MICROS = 1_500
        const val COOL_SLEEP_MICROS = 31_000
        const val TURBO_HASH_MICROS = 0
        const val TURBO_SLEEP_MICROS = 0

        fun hashMicros(turbo: Boolean): Int = if (turbo) TURBO_HASH_MICROS else COOL_HASH_MICROS
        fun sleepMicros(turbo: Boolean): Int = if (turbo) TURBO_SLEEP_MICROS else COOL_SLEEP_MICROS
    }
}
