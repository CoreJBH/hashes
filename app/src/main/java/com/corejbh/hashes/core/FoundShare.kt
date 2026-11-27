package com.corejbh.hashes.core

/**
 * A hash the engine found at or below the job's target, i.e. a share.
 *
 * The digest is the raw SHA-256 output; [hashHex] is the same bytes the way a
 * block hash is written down. Nothing here is submitted anywhere yet: Phase 1
 * stops at finding it.
 */
class FoundShare(val nonce: Long, digest: ByteArray) {

    private val digestBytes: ByteArray = digest.copyOf()

    val hash: ByteArray get() = digestBytes.copyOf()

    val hashHex: String = Hex.toDisplayHex(digestBytes)

    val difficulty: Double = difficultyOf(digestBytes)

    override fun toString(): String = "share nonce=$nonce hash=$hashHex"

    companion object {
        const val DIGEST_SIZE = 32

        /** The difficulty-1 target, 0x00000000FFFF0000...0000. */
        private val DIFFICULTY_ONE_TARGET =
            java.math.BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16)

        fun difficultyOf(digest: ByteArray): Double {
            val reversed = ByteArray(digest.size)
            for (index in digest.indices) {
                reversed[index] = digest[digest.size - 1 - index]
            }
            val value = java.math.BigInteger(1, reversed)
            if (value.signum() == 0) {
                return 0.0
            }
            return DIFFICULTY_ONE_TARGET.toDouble() / value.toDouble()
        }
    }
}
