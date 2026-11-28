package com.corejbh.hashes.session

import com.corejbh.hashes.engine.HashWork
import com.corejbh.hashes.engine.MiningEngine
import com.corejbh.hashes.network.BlockAssembler
import com.corejbh.hashes.network.CoinbaseHeight
import com.corejbh.hashes.network.MiningJob
import com.corejbh.hashes.network.Target
import java.math.BigDecimal
import java.math.BigInteger

/** What a started job hands back, so the session can draw the job it is hashing. */
internal data class StartedJob(
    /** Difficulty of the job's compact target, the network's, for the panel. */
    val networkDifficulty: BigDecimal,
    /** Block height from the coinbase, when the pool puts BIP34 in it. */
    val blockHeight: Long?,
)

/**
 * The job side of a mining session: the extranonce the pool handed out, the
 * counter that keeps two jobs from sharing a coinbase, the pool's share
 * difficulty, and the work the engine is walking right now.
 *
 * These values arrive on the work-event collector and are reset or closed from
 * the terminal's own thread, which is why one [lock] covers all of them and
 * every engine call that depends on them:
 *
 *  - a job cannot start while a stop is closing the session, so a link that
 *    drops mid-job never leaves the engine spinning for a session that ended;
 *  - two jobs cannot pick the same extranonce2, so two coinbases are never the
 *    same;
 *  - a difficulty that arrives while a job runs is applied to the very work the
 *    engine holds, instead of waiting for the next `mining.notify`.
 *
 * ### The target the engine mines against
 *
 * A share is found against the pool's difficulty, not the network's. The network
 * target is what a block has to beat and is far out of reach of a phone: an
 * engine handed it finds nothing to submit, and the pool sees a miner that never
 * sends a share. [Target.fromDifficulty] turns the pool's difficulty into its
 * target. The network target is used only until the pool has announced a
 * difficulty ([poolDifficulty] still zero), because a share the pool would
 * reject is worse than no share at all.
 */
internal class SessionJobs(private val engine: MiningEngine) {

    /** Guards every field below, and the engine calls that read them. */
    private val lock = Any()

    private var extranonce1: String? = null
    private var extranonce2Size: Int = DEFAULT_EXTRANONCE2_SIZE
    private var extranonce2Counter: Long = 0
    private var poolDifficulty: Double = 0.0
    private var networkTarget: BigInteger? = null
    private var activeWork: HashWork? = null

    /** True between [open] and [close]: the session may start and retarget jobs. */
    private var active: Boolean = false

    /** True once [engine] has been started for this session and not stopped since. */
    private var running: Boolean = false

    /**
     * The pool assigned or moved the extranonce. The next job is built from it;
     * a job already running keeps the extranonce2 it was assembled with, because
     * that is the one a share of it has to carry back.
     */
    fun onExtranonce(extranonce1: String, extranonce2Size: Int) {
        synchronized(lock) {
            this.extranonce1 = extranonce1
            this.extranonce2Size = extranonce2Size
        }
    }

    /**
     * A new session is about to start: forget the previous pool's extranonce,
     * counter and difficulty. The engine is started again by the first job.
     */
    fun reset() {
        synchronized(lock) {
            extranonce1 = null
            extranonce2Size = DEFAULT_EXTRANONCE2_SIZE
            extranonce2Counter = 0
            poolDifficulty = 0.0
            networkTarget = null
            activeWork = null
            running = false
        }
    }

    /** The session is live: jobs and difficulties are applied from here on. */
    fun open() {
        synchronized(lock) { active = true }
    }

    /**
     * The session is over. Anything a job start had in flight finds the session
     * closed and leaves the engine alone; the caller stops the engine.
     */
    fun close() {
        synchronized(lock) {
            active = false
            running = false
            activeWork = null
        }
    }

    /**
     * Assembles [job] and hands it to the engine, starting it when this is the
     * first job of the session and swapping the work in otherwise. Returns null
     * when there is no extranonce to build a header from, or the session is
     * closed.
     */
    fun startJob(job: MiningJob): StartedJob? {
        synchronized(lock) {
            val extranonce1 = extranonce1
            if (!active || extranonce1 == null) return null
            extranonce2Counter = (extranonce2Counter + 1) and 0xffffffffL
            val extranonce2 = BlockAssembler.extranonce2Hex(extranonce2Counter, extranonce2Size)
            val target = Target.fromCompact(job.networkBits)
            networkTarget = target
            val work = HashWork(
                jobId = job.jobId,
                headerPrefix = BlockAssembler.headerPrefix(job, extranonce1, extranonce2),
                target = Target.toBytes(shareTarget(target)),
                nonceStart = 0,
                networkTime = job.networkTime,
                extranonce2 = extranonce2,
                difficulty = poolDifficulty,
            )
            activeWork = work
            if (running) {
                engine.updateWork(work)
            } else {
                engine.start(work)
                running = true
            }
            val coinbase = BlockAssembler.coinbase(job.coinbase1, extranonce1, extranonce2, job.coinbase2)
            return StartedJob(
                networkDifficulty = Target.difficulty(job.networkBits),
                blockHeight = CoinbaseHeight.parse(coinbase),
            )
        }
    }

    /**
     * The pool changed its share difficulty.
     *
     * A job that is already running is re-pointed at the new target at once:
     * `mining.set_difficulty` often arrives right after the first job, and
     * waiting for the next `mining.notify` would leave the engine on the old
     * target - the network target, on the first job - for as long as the pool
     * stays quiet. The header is the work the engine already holds, so the
     * extranonce2 a share has to carry is unchanged; only the bar moves, and the
     * engine's own swap restarts its nonce walk from the job's start.
     *
     * Returns true when a running job took the new target.
     */
    fun onDifficulty(difficulty: Double): Boolean {
        synchronized(lock) {
            poolDifficulty = difficulty
            val work = activeWork
            val network = networkTarget
            if (!running || work == null || network == null) return false
            val retargeted = work.copy(
                target = Target.toBytes(shareTarget(network)),
                difficulty = difficulty,
            )
            activeWork = retargeted
            engine.updateWork(retargeted)
            return true
        }
    }

    /**
     * The link is down and the engine has to stop: no difficulty reaches it
     * until the next job starts it again.
     */
    fun stopEngine() {
        synchronized(lock) {
            running = false
            activeWork = null
        }
        engine.stop()
    }

    /** The pool's difficulty when it announced one, the network's until then. */
    private fun shareTarget(networkTarget: BigInteger): BigInteger {
        val difficulty = poolDifficulty
        return if (difficulty.isFinite() && difficulty > 0.0) {
            Target.fromDifficulty(difficulty)
        } else {
            networkTarget
        }
    }

    companion object {
        /** Extranonce2 size assumed before the pool's `mining.subscribe` reply. */
        const val DEFAULT_EXTRANONCE2_SIZE = 4
    }
}
