package com.corejbh.hashes.engine

/**
 * Everything the hash engine needs for one job, already in header byte order.
 *
 * The UI/network layer assembles this once per job (and once per extranonce2
 * value). The native core receives the same three fields and never sees a hex
 * string or a JSON object.
 */
data class HashWork(
    val jobId: String,
    /** First 76 bytes of the 80-byte header; the nonce is the engine's business. */
    val headerPrefix: ByteArray,
    /** 32-byte big-endian target. */
    val target: ByteArray,
    /** Nonce the engine starts at, so a new attempt does not redo old work. */
    val nonceStart: Long = 0,
    /** ntime as sent, needed again when a found share is submitted. */
    val networkTime: String = "",
    /** extranonce2 as sent, needed again when a found share is submitted. */
    val extranonce2: String = "",
    /** Share difficulty the pool assigned, if it announced one. */
    val difficulty: Double = 0.0,
) {
    init {
        require(headerPrefix.size == 76) { "a header prefix is 76 bytes" }
        require(target.size == 32) { "a target is 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HashWork) return false
        return jobId == other.jobId &&
            headerPrefix.contentEquals(other.headerPrefix) &&
            target.contentEquals(other.target) &&
            nonceStart == other.nonceStart &&
            networkTime == other.networkTime &&
            extranonce2 == other.extranonce2 &&
            difficulty == other.difficulty
    }

    override fun hashCode(): Int {
        var result = jobId.hashCode()
        result = 31 * result + headerPrefix.contentHashCode()
        result = 31 * result + target.contentHashCode()
        result = 31 * result + nonceStart.hashCode()
        result = 31 * result + networkTime.hashCode()
        result = 31 * result + extranonce2.hashCode()
        result = 31 * result + difficulty.hashCode()
        return result
    }
}

/** A hash at or below the target, ready to submit. */
data class FoundShare(
    val work: HashWork,
    val nonce: Long,
    val hash: ByteArray,
)

/**
 * One consistent reading of the engine, taken once per UI frame. Arrays are
 * copies, so the caller can read them without racing the worker thread.
 */
data class EngineSnapshot(
    val running: Boolean,
    val turbo: Boolean,
    val hashesTried: Long,
    val hashRate: Double,
    val nonce: Long,
    val currentHash: ByteArray,
    val threads: Int,
    val bestHash: ByteArray?,
    val hardwareSha256: Boolean,
    val engineName: String,
) {
    val currentHashHex: String
        get() = com.corejbh.hashes.crypto.Hex.encode(currentHash.reversedArray())

    val bestHashHex: String?
        get() = bestHash?.let { com.corejbh.hashes.crypto.Hex.encode(it.reversedArray()) }

    companion object {
        val IDLE = EngineSnapshot(
            running = false,
            turbo = false,
            hashesTried = 0,
            hashRate = 0.0,
            nonce = 0,
            currentHash = ByteArray(32),
            threads = 0,
            bestHash = null,
            hardwareSha256 = false,
            engineName = "idle",
        )
    }
}

/**
 * The hash engine contract shared by the Phase 1 native core and the Kotlin
 * fallback used by previews and JVM tests.
 *
 * `start`/`updateWork` hand over an assembled header prefix; `snapshot` is polled
 * about 30 times a second by the UI; `takeFoundShare` drains hashes that met the
 * target, so a submission happens on the UI side where the connection lives.
 */
interface MiningEngine : EnginePowerControl {
    val name: String

    fun start(work: HashWork)
    fun updateWork(work: HashWork)
    fun stop()
    fun snapshot(): EngineSnapshot
    fun takeFoundShare(): FoundShare?
    fun close() {
        stop()
    }
}
