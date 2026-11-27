package com.corejbh.hashes.engine

/**
 * Low-level binding to the Phase 1 native core (`libhashes.so`).
 *
 * The C++ side owns the worker threads, the `std::atomic` counters and the
 * `COOL` duty cycle. Kotlin hands it bytes and reads numbers back; nothing is
 * called per nonce across JNI. The exact symbol list and counter layout are
 * written down in `docs/native-bridge-contract.md`, which is the document the
 * native side has to match.
 *
 * The interface exists so [NativeMiningEngine] can be tested on the JVM with a
 * fake binding, and so a different backend (for example a future ARMv9 path)
 * can be dropped in without touching the UI.
 */
interface NativeMinerBinding {

    val available: Boolean

    /**
     * Starts worker threads on [headerPrefix] with [nonceStart].
     *
     * @param turbo true for the ARMv8 SHA2 multi-thread path, false for COOL.
     * @param dutyHashMicros hashing window per frame in COOL mode.
     * @param dutySleepMicros sleep window per frame in COOL mode.
     */
    fun start(
        headerPrefix: ByteArray,
        target: ByteArray,
        nonceStart: Long,
        turbo: Boolean,
        dutyHashMicros: Int,
        dutySleepMicros: Int,
    ): Boolean

    /** Swaps the job in place; worker threads pick the new midstate up. */
    fun updateWork(headerPrefix: ByteArray, target: ByteArray, nonceStart: Long): Boolean

    fun setTurboMode(turbo: Boolean)

    /**
     * Caps how many workers TURBO may use: the MODERATE thermal step.
     * The native core clamps it to the device's worker limit and restarts the
     * workers only when the number actually changes.
     */
    fun setThreadCap(threads: Int)

    fun stop()

    /** Fills the eight counter slots described by [Counter]. */
    fun pollCounters(out: LongArray)

    /**
     * Fills [out] ([MAX_THREADS] long) with the cumulative hashes of each worker
     * and returns the worker count of the current mode. Slots past that count
     * are zero; the panel turns the deltas into a per-worker rate.
     */
    fun pollThreadHashes(out: LongArray): Int

    /** Fills 32 bytes with the most recently hashed digest, internal order. */
    fun pollCurrentHash(out: ByteArray)

    /** Fills 32 bytes with the best digest this session and returns true, or false when none. */
    fun pollBestHash(out: ByteArray): Boolean

    /** Returns the nonce of a target-meeting hash and fills [out], or -1 when there is none. */
    fun pollFoundHash(out: ByteArray): Long

    /** Native core version string, for the terminal header. */
    fun version(): String

    /** Counter slot positions for [pollCounters]. */
    object Counter {
        const val STATE = 0
        const val HASHES = 1
        const val NONCE = 2
        const val THREADS = 3
        const val HASH_RATE = 4
        const val BEST_LEADING_ZEROS = 5
        const val HARDWARE_FLAG = 6
        const val RESERVED = 7

        const val SIZE = 8

        const val STATE_IDLE = 0L
        const val STATE_RUNNING = 1L
        const val STATE_STOPPING = 2L
        const val STATE_ERROR = 3L
    }

    companion object {
        /** Library name the packaging step must produce: libhashes.so. */
        const val LIBRARY = "hashes"

        /** Worker slots [pollThreadHashes] can fill: the native core's limit. */
        const val MAX_THREADS = 8
    }
}
