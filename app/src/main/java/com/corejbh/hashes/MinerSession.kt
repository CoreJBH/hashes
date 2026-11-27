package com.corejbh.hashes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.corejbh.hashes.core.EngineCounters
import com.corejbh.hashes.core.MinerMode
import com.corejbh.hashes.core.SelfTestJob
import com.corejbh.hashes.engine.NativeMiner
import com.corejbh.hashes.thermal.ThermalController
import com.corejbh.hashes.thermal.ThermalDecision
import com.corejbh.hashes.thermal.ThermalMonitor
import com.corejbh.hashes.thermal.TurboEngineControl
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns what lives as long as the screen: the native engine, the thermal monitor
 * that watches it, and whether it is running.
 *
 * The app's rule so far is foreground-only, so [pause] stops the engine whenever
 * the screen goes away and a run does not restart by itself. The monitor runs
 * only while mining does.
 */
class MinerSession(context: Context) : TurboEngineControl {

    private val controller = ThermalController(this, maxWorkerCount())

    private val monitor = ThermalMonitor(context) { decision -> controller.onDecision(decision) }

    val thermalDecision: StateFlow<ThermalDecision> = controller.decision

    var mode by mutableStateOf(MinerMode.COOL)
        private set

    var running by mutableStateOf(false)
        private set

    private val counterScratch = LongArray(NativeMiner.COUNTER_SLOTS)
    private val digestScratch = ByteArray(NativeMiner.DIGEST_SIZE)

    val available: Boolean get() = NativeMiner.available

    val version: String get() = if (available) NativeMiner.nativeVersion() else "unavailable"

    fun maxThreads(): Int = controller.maxThreads

    fun unavailableReason(): String? = NativeMiner.loadFailure?.message

    fun start() {
        if (!available) {
            return
        }
        val job = SelfTestJob.blockZero()
        val turbo = mode == MinerMode.TURBO
        val started = NativeMiner.nativeStart(
            headerPrefix = job.headerPrefix,
            target = job.target,
            nonceStart = job.nonceStart,
            turbo = turbo,
            dutyHashMicros = if (turbo) 0 else COOL_DUTY_HASH_MICROS,
            dutySleepMicros = if (turbo) 0 else COOL_DUTY_SLEEP_MICROS,
        )
        running = started
        if (started) {
            monitor.start()
        }
    }

    fun stop() {
        running = false
        if (available) {
            NativeMiner.nativeStop()
        }
        monitor.stop()
    }

    fun selectMode(mode: MinerMode) {
        controller.onUserMode(mode)
        this.mode = mode
    }

    fun counters(): EngineCounters {
        if (!available) {
            return EngineCounters.IDLE
        }
        NativeMiner.nativePollCounters(counterScratch)
        return EngineCounters.from(counterScratch)
    }

    /** The newest digest for the live stream, or null when nothing has hashed yet. */
    fun currentHash(): ByteArray? {
        if (!available) {
            return null
        }
        NativeMiner.nativePollCurrentHash(digestScratch)
        return digestScratch.copyOf()
    }

    /** The oldest found hash's nonce, or -1; the digest lands in [digest]. */
    fun takeFoundHash(digest: ByteArray): Long =
        if (available) NativeMiner.nativePollFoundHash(digest) else -1L

    /** Only ever stops the engine: a pause never starts one. */
    fun pause() {
        if (running) {
            stop()
        }
    }

    fun close() {
        running = false
        monitor.stop()
        if (available) {
            NativeMiner.nativeStop()
        }
    }

    override fun setTurboMode(turbo: Boolean) {
        mode = if (turbo) MinerMode.TURBO else MinerMode.COOL
        if (available) {
            NativeMiner.nativeSetTurboMode(turbo)
        }
    }

    override fun setThreadCap(threads: Int) {
        if (available) {
            NativeMiner.nativeSetThreadCap(threads)
        }
    }

    companion object {
        /** COOL: 1.5 ms of hashing in every ~32.5 ms window, a 30 fps duty cycle. */
        const val COOL_DUTY_HASH_MICROS = 1500
        const val COOL_DUTY_SLEEP_MICROS = 31000

        /** Mirrors the native worker limit: one core left for the system, at most eight. */
        fun maxWorkerCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores > 1) minOf(cores - 1, 8) else 1
        }
    }
}
