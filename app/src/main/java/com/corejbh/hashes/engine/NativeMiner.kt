package com.corejbh.hashes.engine

/**
 * The bridge to `libhashes.so`, exactly as the bridge contract defines it:
 * `docs/native-bridge-contract.md`.
 *
 * Nine methods, no per-hash calls. Kotlin hands over one job, flips the mode,
 * and then reads counters and hashes about thirty times a second. Buffers are
 * reused between calls: the native side copies what it needs before returning.
 *
 * A load failure is not fatal. [available] goes false, the rest of the app can
 * fall back to the Kotlin engine, and nothing is called into a library that is
 * not there.
 */
object NativeMiner {

    /** Slots in the array [nativePollCounters] fills. */
    const val COUNTER_SLOTS = 8

    /** Digest and header sizes the native calls expect. */
    const val DIGEST_SIZE = 32
    const val HEADER_PREFIX_SIZE = 76

    val loadFailure: Throwable? = try {
        System.loadLibrary("hashes")
        null
    } catch (failure: Throwable) {
        failure
    }

    val available: Boolean get() = loadFailure == null

    /**
     * Starts a job: 76 header bytes (the nonce is written by the core), a
     * big-endian target, and the duty cycle. Returns false when the workers
     * could not be created.
     */
    external fun nativeStart(
        headerPrefix: ByteArray,
        target: ByteArray,
        nonceStart: Long,
        turbo: Boolean,
        dutyHashMicros: Int,
        dutySleepMicros: Int,
    ): Boolean

    /** Swaps the job without stopping the workers. */
    external fun nativeUpdateWork(
        headerPrefix: ByteArray,
        target: ByteArray,
        nonceStart: Long,
    ): Boolean

    external fun nativeSetTurboMode(turbo: Boolean)

    external fun nativeStop()

    external fun nativePollCounters(out: LongArray)

    external fun nativePollCurrentHash(out: ByteArray)

    /** True when a best hash exists and was written to `out`. */
    external fun nativePollBestHash(out: ByteArray): Boolean

    /** The nonce of the oldest found hash, or -1 when none is waiting. */
    external fun nativePollFoundHash(out: ByteArray): Long

    external fun nativeVersion(): String

    /**
     * Caps how many workers TURBO may use.
     *
     * This is the one method outside the bridge contract. It exists because the
     * finer thermal step (MODERATE halves the threads) was designed into the
     * native core but the contract has no way to reach it; a binding that does
     * not declare it simply keeps the full worker set.
     */
    external fun nativeSetThreadCap(threads: Int)
}
