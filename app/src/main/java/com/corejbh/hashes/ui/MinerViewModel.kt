package com.corejbh.hashes.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.data.MinerSettings
import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.session.MiningSession
import com.corejbh.hashes.service.MinerServiceCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The terminal's view of the mining session.
 *
 * The session itself is owned by [MiningSession] and kept alive by
 * `MinerForegroundService`, so this class never starts a socket or a worker: it
 * forwards the buttons to the service and turns the session's state into frames.
 * The frame loop is the only thing tied to the screen — it polls the engine at
 * 30 fps, extends the hash stream and the graphs, and stops on `ON_STOP` while
 * the session keeps hashing and the notification keeps counting.
 */
class MinerViewModel(application: Application) : AndroidViewModel(application) {

    private val session = MiningSession.get(application)
    private val stream = HashStream()

    private val _state = MutableStateFlow(MinerUiState(session = session.state.value))
    val state: StateFlow<MinerUiState> = _state.asStateFlow()

    private var frameJob: Job? = null

    /** Cumulative per-worker counters of the previous rate sample. */
    private var previousWorkerHashes: List<Long> = emptyList()
    private var previousWorkerAt = 0L
    private var workerRates: List<Double> = emptyList()
    private val rateHistory = ArrayDeque<Double>()
    private val temperatureHistory = ArrayDeque<Double>()

    init {
        viewModelScope.launch {
            session.state.collect { current ->
                _state.value = _state.value.copy(session = current)
            }
        }
        startFrames()
    }

    // ---------------------------------------------------------------- controls

    /**
     * Starts a session and puts it in the foreground. Returns false when the
     * payout target or the endpoint cannot be used, so the terminal can open the
     * settings sheet instead.
     */
    fun startMining(): Boolean {
        val settings = _state.value.settings
        if (BitcoinAddressValidator.validate(settings.payoutAddress) !is BitcoinAddressValidator.Result.Valid) {
            return false
        }
        if (settings.endpointOrNull() == null) return false
        if (!session.start()) return false
        stream.clear()
        resetWorkerBaseline()
        rateHistory.clear()
        temperatureHistory.clear()
        MinerServiceCommands.start(getApplication())
        return true
    }

    /** Stops hashing for real: the session, the socket and the notification. */
    fun stopMining() {
        session.stop()
        MinerServiceCommands.stop(getApplication())
    }

    fun onScreenVisible() {
        startFrames()
    }

    /** The screen went away: only the frame polling stops, the session does not. */
    fun onScreenHidden() {
        frameJob?.cancel()
        frameJob = null
    }

    /** Resets the expiring thermal guard so the user can try TURBO again. */
    fun clearThermalGuard() {
        session.clearThermalGuard()
    }

    fun setTurbo(enabled: Boolean) {
        session.setTurbo(enabled)
    }

    fun applySettings(newSettings: MinerSettings) {
        session.applySettings(newSettings)
    }

    // ------------------------------------------------------------- frame loop

    private fun startFrames() {
        if (frameJob != null) return
        resetWorkerBaseline()
        frameJob = viewModelScope.launch {
            var frame = 0L
            while (isActive) {
                val snapshot = session.sampleEngine()
                val current = session.state.value
                if (snapshot.running) {
                    val hex = snapshot.currentHashHex
                    if (hex != ZERO_HASH) stream.push(hex)
                }
                sampleWorkerRates(snapshot)
                if (frame % HISTORY_EVERY_FRAMES == 0L) {
                    recordHistory(snapshot, current.temperatureCelsius)
                }
                _state.value = _state.value.copy(
                    session = current,
                    snapshot = snapshot,
                    stream = stream.lines(),
                    workerRates = workerRates,
                    rateHistory = rateHistory.toList(),
                    temperatureHistory = temperatureHistory.toList(),
                )
                frame += 1
                delay(FRAME_MILLIS)
            }
        }
    }

    /**
     * One worker-rate sample per second, from the cumulative counters. A worker
     * set that changed size is not compared against the old one: the engine
     * restarted its slots, so the measurement starts again from zero.
     */
    private fun sampleWorkerRates(snapshot: EngineSnapshot) {
        val hashes = snapshot.workerHashes.map { it }
        val now = SystemClock.elapsedRealtime()
        /* A new job clears the per-worker totals, so a slot that went backwards
         * means the counters restarted: measure again from here, never report a
         * negative or a made-up rate. */
        val restarted = hashes.size != previousWorkerHashes.size ||
            hashes.indices.any {
                it < previousWorkerHashes.size && hashes[it] < previousWorkerHashes[it]
            }
        if (previousWorkerAt == 0L || restarted) {
            previousWorkerHashes = hashes
            previousWorkerAt = now
            workerRates = List(hashes.size) { 0.0 }
            return
        }
        val seconds = (now - previousWorkerAt) / 1000.0
        if (seconds < RATE_SAMPLE_SECONDS) return
        workerRates = WorkerMeter.rates(previousWorkerHashes, hashes, seconds)
        previousWorkerHashes = hashes
        previousWorkerAt = now
    }

    private fun resetWorkerBaseline() {
        previousWorkerHashes = emptyList()
        previousWorkerAt = 0L
        workerRates = emptyList()
    }

    private fun recordHistory(snapshot: EngineSnapshot, temperatureCelsius: Double?) {
        rateHistory.addLast(snapshot.hashRate)
        while (rateHistory.size > HISTORY_SAMPLES) rateHistory.removeFirst()
        if (temperatureCelsius != null) {
            temperatureHistory.addLast(temperatureCelsius)
            while (temperatureHistory.size > HISTORY_SAMPLES) temperatureHistory.removeFirst()
        }
    }

    companion object {
        const val FRAME_MILLIS = 33L

        /** History is written once a second, however fast the frames are. */
        const val HISTORY_EVERY_FRAMES = 30L

        /** How many seconds of graph the sparkline holds. */
        const val HISTORY_SAMPLES = 24

        /** Worker rates need a whole second between counter reads to mean much. */
        const val RATE_SAMPLE_SECONDS = 0.9

        private val ZERO_HASH = "0".repeat(64)
    }
}
