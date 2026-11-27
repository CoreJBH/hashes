package com.corejbh.hashes.core

/** The state the native engine reports in counter slot 0. */
enum class EngineState(val code: Long) {
    IDLE(0),
    RUNNING(1),
    STOPPING(2),
    ERROR(3),
    ;

    companion object {
        fun fromCode(code: Long): EngineState =
            entries.firstOrNull { it.code == code } ?: IDLE
    }
}

/**
 * One reading of the engine, from the eight-long counters array the bridge
 * fills. Nothing here blocks on the workers: every field is an atomic read on
 * the native side.
 */
data class EngineCounters(
    val state: EngineState,
    val hashes: Long,
    val nonce: Long,
    val threads: Int,
    val hashRate: Long,
    val bestLeadingZeroNibbles: Int,
    val hardwareSha256: Boolean,
) {
    val running: Boolean get() = state == EngineState.RUNNING

    companion object {
        /** Slot indexes, shared with the native side. */
        const val INDEX_STATE = 0
        const val INDEX_HASHES = 1
        const val INDEX_NONCE = 2
        const val INDEX_THREADS = 3
        const val INDEX_HASH_RATE = 4
        const val INDEX_BEST_ZERO_NIBBLES = 5
        const val INDEX_FLAGS = 6
        const val INDEX_RESERVED = 7

        /** Bit 0 of the flags slot: the ARMv8 SHA-256 path is in use. */
        const val FLAG_HARDWARE_SHA256 = 1L

        val IDLE = EngineCounters(
            state = EngineState.IDLE,
            hashes = 0L,
            nonce = 0L,
            threads = 0,
            hashRate = 0L,
            bestLeadingZeroNibbles = 0,
            hardwareSha256 = false,
        )

        fun from(counters: LongArray): EngineCounters = EngineCounters(
            state = EngineState.fromCode(counters[INDEX_STATE]),
            hashes = counters[INDEX_HASHES],
            nonce = counters[INDEX_NONCE],
            threads = counters[INDEX_THREADS].toInt(),
            hashRate = counters[INDEX_HASH_RATE],
            bestLeadingZeroNibbles = counters[INDEX_BEST_ZERO_NIBBLES].toInt(),
            hardwareSha256 = (counters[INDEX_FLAGS] and FLAG_HARDWARE_SHA256) != 0L,
        )
    }
}
