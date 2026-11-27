package com.corejbh.hashes.core

/**
 * A fixed job for the Phase 1 placeholder screen: block 0's header against the
 * difficulty-1 target it was mined with.
 *
 * It exists so the engine can be exercised on a device before any networking
 * exists, and it is honest about what it is: at difficulty 1 a phone will not
 * find a share in a session, so a run shows a hashrate and a moving nonce and
 * nothing else. The network phase replaces it with a job from a pool.
 */
object SelfTestJob {

    /** Block 0's header, first 76 bytes: everything but the nonce. */
    private const val GENESIS_PREFIX_HEX =
        "0100000000000000000000000000000000000000000000000000000000000000" +
            "000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa" +
            "4b1e5e4a29ab5f49ffff001d"

    /** The difficulty-1 target, 0x00000000FFFF0000...0000, big-endian. */
    fun difficultyOneTarget(): ByteArray = ByteArray(MiningJob.TARGET_SIZE).also { target ->
        target[4] = 0xFF.toByte()
        target[5] = 0xFF.toByte()
    }

    fun difficultyOneBits(): Long = 0x1D00FFFFL

    fun blockZero(): MiningJob = MiningJob(
        headerPrefix = Hex.parse(GENESIS_PREFIX_HEX),
        target = difficultyOneTarget(),
    )
}
