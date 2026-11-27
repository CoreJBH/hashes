package com.corejbh.hashes.engine

import kotlin.math.max

/**
 * Adapts [NativeMinerBinding] to the [MiningEngine] contract the UI polls.
 *
 * Everything here is a translation: the counters the native core publishes become
 * an [EngineSnapshot], and a target-meeting hash the core queued becomes a
 * [FoundShare] carrying the work it belongs to. The engine never talks to the
 * network, and nothing is invented when the core reports no found share.
 */
class NativeMiningEngine(
    private val binding: NativeMinerBinding = JniMinerBinding(),
) : MiningEngine {

    override val name: String = "native"

    @Volatile
    private var work: HashWork? = null

    @Volatile
    private var turbo = false

    private val counters = LongArray(NativeMinerBinding.Counter.SIZE)
    private val hashBuffer = ByteArray(32)
    private val bestBuffer = ByteArray(32)
    private val foundBuffer = ByteArray(32)

    private var lastSampleNanos = 0L
    private var lastSampleHashes = 0L
    private var smoothedRate = 0.0

    fun isAvailable(): Boolean = binding.available

    fun version(): String = binding.version()

    override fun start(work: HashWork) {
        this.work = work
        synchronized(this) {
            lastSampleNanos = System.nanoTime()
            lastSampleHashes = 0
            smoothedRate = 0.0
        }
        binding.start(
            headerPrefix = work.headerPrefix,
            target = work.target,
            nonceStart = work.nonceStart,
            turbo = turbo,
            dutyHashMicros = DutyCycle.hashMicros(turbo),
            dutySleepMicros = DutyCycle.sleepMicros(turbo),
        )
    }

    override fun updateWork(work: HashWork) {
        this.work = work
        binding.updateWork(work.headerPrefix, work.target, work.nonceStart)
    }

    override fun setTurboMode(enabled: Boolean) {
        turbo = enabled
        binding.setTurboMode(enabled)
    }

    /** The MODERATE thermal step: TURBO keeps running on half the workers. */
    override fun setThreadCap(threads: Int) {
        binding.setThreadCap(threads)
    }

    override fun stop() {
        binding.stop()
    }

    override fun snapshot(): EngineSnapshot {
        synchronized(this) {
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
            return EngineSnapshot(
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
            )
        }
    }

    override fun takeFoundShare(): FoundShare? {
        val currentWork = work ?: return null
        val nonce = binding.pollFoundHash(foundBuffer)
        if (nonce < 0) return null
        return FoundShare(work = currentWork, nonce = nonce and 0xffffffffL, hash = foundBuffer.copyOf())
    }

    override fun close() {
        stop()
        work = null
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
