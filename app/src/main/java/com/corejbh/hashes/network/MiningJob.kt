package com.corejbh.hashes.network

import com.corejbh.hashes.crypto.Hex

/**
 * One `mining.notify` job, exactly as the pool sent it.
 *
 * Nothing is assembled here: the job is data, and [BlockAssembler] turns it into
 * the 80-byte header the hash engine walks. Field names follow the Stratum V1
 * ones so a reader can compare this with the protocol description.
 */
data class MiningJob(
    val jobId: String,
    val previousHash: String,
    val coinbase1: String,
    val coinbase2: String,
    val merkleBranches: List<String>,
    val version: String,
    val networkBits: String,
    val networkTime: String,
    val cleanJobs: Boolean,
) {
    init {
        require(jobId.isNotEmpty()) { "job id is empty" }
        require(Hex.isHex(previousHash, 64)) { "previous hash is not 32 bytes of hex" }
        require(Hex.isHex(coinbase1)) { "coinbase1 is not hex" }
        require(Hex.isHex(coinbase2)) { "coinbase2 is not hex" }
        require(Hex.isHex(version, 8)) { "version is not 4 bytes of hex" }
        require(Hex.isHex(networkBits, 8)) { "network bits are not 4 bytes of hex" }
        require(Hex.isHex(networkTime, 8)) { "network time is not 4 bytes of hex" }
        require(merkleBranches.all { Hex.isHex(it, 64) }) { "a merkle branch is not 32 bytes of hex" }
    }

    companion object {
        const val MIN_PARAMETERS = 9

        /**
         * Builds a job from the `mining.notify` parameter array. Throws
         * [StratumFormatException] when a pool sends something the assembler
         * cannot use, so a malformed job never reaches the hash engine.
         */
        fun fromNotify(params: List<Any?>): MiningJob {
            if (params.size < MIN_PARAMETERS) {
                throw StratumFormatException("mining.notify sent ${params.size} parameters")
            }
            val jobId = params[0] as? String ?: throw StratumFormatException("job id is not a string")
            val previousHash = params[1] as? String
                ?: throw StratumFormatException("previous hash is not a string")
            val coinbase1 = params[2] as? String
                ?: throw StratumFormatException("coinbase1 is not a string")
            val coinbase2 = params[3] as? String
                ?: throw StratumFormatException("coinbase2 is not a string")
            val branches = params[4] as? List<*>
                ?: throw StratumFormatException("merkle branches are not an array")
            val version = params[5] as? String ?: throw StratumFormatException("version is not a string")
            val bits = params[6] as? String ?: throw StratumFormatException("nbits is not a string")
            val time = params[7] as? String ?: throw StratumFormatException("ntime is not a string")
            val clean = when (val flag = params[8]) {
                is Boolean -> flag
                is Number -> flag.toInt() != 0
                else -> throw StratumFormatException("clean_jobs is not a boolean")
            }
            return try {
                MiningJob(
                    jobId = jobId,
                    previousHash = previousHash,
                    coinbase1 = coinbase1,
                    coinbase2 = coinbase2,
                    merkleBranches = branches.map { branch ->
                        branch as? String ?: throw StratumFormatException("a merkle branch is not a string")
                    },
                    version = version,
                    networkBits = bits,
                    networkTime = time,
                    cleanJobs = clean,
                )
            } catch (error: IllegalArgumentException) {
                throw StratumFormatException(error.message ?: "malformed job")
            }
        }
    }
}

/** A pool message this client cannot use. Never fatal to the connection loop. */
class StratumFormatException(message: String) : Exception(message)
