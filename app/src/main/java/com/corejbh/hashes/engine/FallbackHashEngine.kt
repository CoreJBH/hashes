package com.corejbh.hashes.engine

import com.corejbh.hashes.crypto.Sha256
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

/**
 * A real, slow, pure-Kotlin hash engine used where the native core is absent:
 * JVM unit tests, Compose previews, and an emulator without the arm64 library.
 *
 * It is not a simulation. It assembles the same 80-byte header the native core
 * would, precomputes the midstate once per job, and hashes nonces for real, so a
 * hash it reports as meeting the target does meet it. What it cannot do is run at
 * a phone's speed: it is a correctness and preview fallback, and the UI says so
 * in the status line (`ENGINE: KOTLIN-FALLBACK`).
 *
 * COOL keeps the same duty cycle as the native design - about 1.5 ms of hashing
 * per 33 ms frame - so the fallback also stays cool. TURBO hashes continuously
 * on one thread.
 */
class FallbackHashEngine(
    private val clockNanos: () -> Long = System::nanoTime,
) : MiningEngine {

    override val name: String = "kotlin-fallback"

    @Volatile
    private var running = false

    @Volatile
    private var turbo = false

    @Volatile
    private var work: HashWork? = null

    @Volatile
    private var hashes: Long = 0

    @Volatile
    private var nonce: Long = 0

    private val hotHash = ByteArray(32)
    private val bestLock = Any()
    private var bestHash: ByteArray? = null
    private var bestZeros: Int = -1

    private val found = LinkedBlockingQueue<FoundShare>(16)
    private var thread: Thread? = null

    private var lastRateNanos = 0L
    private var lastRateHashes = 0L
    private var smoothedRate = 0.0

    override fun start(work: HashWork) {
        stop()
        this.work = work
        this.hashes = 0
        this.nonce = work.nonceStart
        this.smoothedRate = 0.0
        this.lastRateNanos = clockNanos()
        this.lastRateHashes = 0
        this.found.clear()
        synchronized(bestLock) {
            bestHash = null
            bestZeros = -1
        }
        running = true
        thread = Thread({ hashLoop() }, "hashes-fallback").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
            start()
        }
    }

    override fun updateWork(work: HashWork) {
        this.work = work
        this.nonce = work.nonceStart
    }

    override fun setTurboMode(enabled: Boolean) {
        turbo = enabled
    }

    override fun stop() {
        running = false
        val current = thread
        thread = null
        current?.interrupt()
        try {
            current?.join(STOP_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun snapshot(): EngineSnapshot {
        val now = clockNanos()
        val rate = synchronized(this) {
            val deltaHashes = hashes - lastRateHashes
            val deltaSeconds = (now - lastRateNanos) / 1_000_000_000.0
            if (deltaSeconds > 0.05) {
                val instant = deltaHashes / deltaSeconds
                smoothedRate = if (smoothedRate == 0.0) instant else smoothedRate * 0.7 + instant * 0.3
                lastRateHashes = hashes
                lastRateNanos = now
            }
            smoothedRate
        }
        val best = synchronized(bestLock) { bestHash?.copyOf() }
        val hashTotal = hashes
        return EngineSnapshot(
            running = running,
            turbo = turbo,
            hashesTried = hashTotal,
            hashRate = rate,
            nonce = nonce,
            currentHash = synchronized(this) { hotHash.copyOf() },
            threads = if (running) 1 else 0,
            bestHash = best,
            hardwareSha256 = false,
            engineName = name,
            // One thread, so the panel has one slot and it is the whole total.
            workerHashes = if (running) longArrayOf(hashTotal) else LongArray(0),
        )
    }

    override fun takeFoundShare(): FoundShare? = found.poll()

    override fun close() {
        stop()
        work = null
    }

    private fun hashLoop() {
        val stateScratch = IntArray(8)
        val block = ByteArray(128)
        val digest = ByteArray(32)
        val tail = ByteArray(16)

        var activeWork: HashWork? = null
        var midstate = IntArray(8)
        var targetBytes = ByteArray(32)

        while (running) {
            val current = work
            if (current == null) {
                sleepQuietly(5)
                continue
            }
            if (activeWork !== current) {
                activeWork = current
                midstate = Sha256.midstate(current.headerPrefix.copyOfRange(0, 64))
                System.arraycopy(current.headerPrefix, 64, tail, 0, 12)
                targetBytes = current.target.copyOf()
                if (nonce == 0L) nonce = current.nonceStart
            }

            val windowNanos = if (turbo) TURBO_WINDOW_NANOS else COOL_WINDOW_NANOS
            val windowStart = clockNanos()
            while (running && work === current && clockNanos() - windowStart < windowNanos) {
                var localNonce = nonce
                var localHashes = hashes
                var count = 0
                while (count < BATCH && running && work === current) {
                    tail[12] = localNonce.toByte()
                    tail[13] = (localNonce ushr 8).toByte()
                    tail[14] = (localNonce ushr 16).toByte()
                    tail[15] = (localNonce ushr 24).toByte()
                    Sha256.doubleHash80(midstate, tail, stateScratch, block, digest)
                    System.arraycopy(digest, 0, hotHash, 0, 32)
                    localHashes += 1
                    count += 1
                    if (meetsTarget(digest, targetBytes)) {
                        found.offer(FoundShare(current, localNonce and 0xffffffffL, digest.copyOf()))
                        val zeros = leadingZeroNibbles(digest)
                        synchronized(bestLock) {
                            if (zeros > bestZeros) {
                                bestZeros = zeros
                                bestHash = digest.copyOf()
                            }
                        }
                    }
                    localNonce = (localNonce + 1) and 0xffffffffL
                }
                nonce = localNonce
                hashes = localHashes
            }

            if (!turbo) sleepQuietly(COOL_SLEEP_MS)
        }
    }

    private fun meetsTarget(digest: ByteArray, target: ByteArray): Boolean {
        for (index in 0 until 32) {
            val candidate = digest[31 - index].toInt() and 0xff
            val limit = target[index].toInt() and 0xff
            if (candidate != limit) return candidate < limit
        }
        return true
    }

    private fun leadingZeroNibbles(digest: ByteArray): Int {
        var zeros = 0
        for (index in 31 downTo 0) {
            val high = (digest[index].toInt() ushr 4) and 0x0f
            if (high != 0) return zeros
            zeros += 1
            val low = digest[index].toInt() and 0x0f
            if (low != 0) return zeros
            zeros += 1
        }
        return zeros
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(max(1L, millis))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {
        const val COOL_WINDOW_NANOS = 1_500_000L
        const val COOL_SLEEP_MS = 31L
        const val TURBO_WINDOW_NANOS = 20_000_000L
        private const val BATCH = 2_048
        private const val STOP_JOIN_MS = 600L
    }
}
