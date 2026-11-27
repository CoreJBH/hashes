package com.corejbh.hashes.core

/**
 * A job as the engine receives it.
 *
 * `headerPrefix` is the first 76 bytes of the 80-byte block header: version,
 * previous hash, merkle root, ntime and nbits. The nonce's four bytes are
 * written by the core, which is why they are not here.
 */
class MiningJob(
    val headerPrefix: ByteArray,
    val target: ByteArray,
    val nonceStart: Long = 0L,
) {
    init {
        require(headerPrefix.size == PREFIX_SIZE) { "header prefix must be $PREFIX_SIZE bytes" }
        require(target.size == TARGET_SIZE) { "target must be $TARGET_SIZE bytes" }
        require(nonceStart in 0L..MAX_NONCE) { "nonceStart out of range" }
    }

    /**
     * nbits of the header, from the last four bytes of the prefix. Kept here so
     * a UI can show the job's target class without re-parsing the header.
     */
    val bits: Long
        get() = (headerPrefix[72].toLong() and 0xFF) or
            ((headerPrefix[73].toLong() and 0xFF) shl 8) or
            ((headerPrefix[74].toLong() and 0xFF) shl 16) or
            ((headerPrefix[75].toLong() and 0xFF) shl 24)

    companion object {
        const val PREFIX_SIZE = 76
        const val TARGET_SIZE = 32
        const val MAX_NONCE = 0xFFFFFFFFL
    }
}
