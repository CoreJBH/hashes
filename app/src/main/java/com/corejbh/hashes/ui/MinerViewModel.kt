package com.corejbh.hashes.ui

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.data.BestShareRecord
import com.corejbh.hashes.data.BestShareTracker
import com.corejbh.hashes.data.MinerSettings
import com.corejbh.hashes.data.SharedPreferencesBestSharePersistence
import com.corejbh.hashes.data.SharedPreferencesSettingsStore
import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.engine.FallbackHashEngine
import com.corejbh.hashes.engine.HashWork
import com.corejbh.hashes.engine.JniMinerBinding
import com.corejbh.hashes.engine.MiningEngine
import com.corejbh.hashes.engine.NativeMiningEngine
import com.corejbh.hashes.network.BlockAssembler
import com.corejbh.hashes.network.CoinbaseHeight
import com.corejbh.hashes.network.ConnectionState
import com.corejbh.hashes.network.MiningJob
import com.corejbh.hashes.network.MiningWorkSource
import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.network.ShareSubmission
import com.corejbh.hashes.network.StratumClient
import com.corejbh.hashes.network.Target
import com.corejbh.hashes.network.WorkEvent
import com.corejbh.hashes.thermal.ThermalGuard
import com.corejbh.hashes.thermal.ThermalMonitor
import java.math.BigDecimal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the terminal draws, produced once per frame. */
data class MinerUiState(
    val settings: MinerSettings = MinerSettings(),
    val endpoint: PoolEndpoint? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionDetail: String = "OFFLINE",
    val mining: Boolean = false,
    val job: MiningJob? = null,
    val blockHeight: Long? = null,
    val networkBits: String? = null,
    val networkDifficulty: BigDecimal? = null,
    val shareDifficulty: Double = 0.0,
    val snapshot: EngineSnapshot = EngineSnapshot.IDLE,
    val stream: List<HashStream.Sample> = emptyList(),
    val sessionBest: BestShareRecord? = null,
    val allTimeBest: BestShareRecord? = null,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val status: String = "IDLE",
    val notices: List<String> = emptyList(),
    val temperatureCelsius: Double? = null,
    val thermalGuard: Boolean = false,
    val expectedSeconds: Double = Double.POSITIVE_INFINITY,
    val ticket: Long = 0,
) {
    val payoutAddress: String
        get() = settings.payoutAddress
}

/**
 * Owns the mining session: settings, the work source, the hash engine and the
 * frame loop that turns engine counters into the terminal's state.
 *
 * The selection of engine and work source is made once here, through their
 * interfaces; the UI has no knowledge of JNI or sockets.
 */
class MinerViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SharedPreferencesSettingsStore(application)
    private val tracker = BestShareTracker(SharedPreferencesBestSharePersistence(application))

    private val engine: MiningEngine = createEngine()

    private val workSource: MiningWorkSource = StratumClient()

    /** Reads the device's heat and feeds the two-step guard below. */
    private val thermalMonitor = ThermalMonitor(application) { decision ->
        thermalGuard.onReading(decision)
    }

    /** MODERATE halves TURBO's workers, two SEVERE readings take TURBO away. */
    private val thermalGuard = ThermalGuard(engine, maxWorkerCount())

    private val _state = MutableStateFlow(
        MinerUiState(
            settings = settingsStore.load(),
            sessionBest = tracker.sessionBest(),
            allTimeBest = tracker.allTimeBest(),
        ).let { initial ->
            initial.copy(
                endpoint = initial.settings.endpoint(),
                snapshot = engine.snapshot(),
            )
        }
    )
    val state: StateFlow<MinerUiState> = _state.asStateFlow()

    private val stream = HashStream()
    private var sessionExtranonce1: String? = null
    private var sessionExtranonce2Size: Int = 4
    private var extranonce2Counter: Long = 0
    private var moduleDifficulty: Double = 0.0
    private var targetValue: BigDecimal? = null
    private var pendingJob: MiningJob? = null
    private var ticker: Job? = null
    private var engineRunning: Boolean = false

    init {
        thermalGuard.onUserTurbo(_state.value.settings.turbo)
        observeWorkEvents()
        observeThermalGuard()
        startTicker()
    }

    private fun createEngine(): MiningEngine {
        val binding = JniMinerBinding()
        return if (binding.available) NativeMiningEngine(binding) else FallbackHashEngine()
    }

    val engineIsNative: Boolean
        get() = engine is NativeMiningEngine && (engine as NativeMiningEngine).isAvailable()

    // ---------------------------------------------------------------- controls

    /** Starts a session. Returns false when the payout target is not usable. */
    fun startMining(): Boolean {
        val settings = _state.value.settings
        if (BitcoinAddressValidator.validate(settings.payoutAddress) !is BitcoinAddressValidator.Result.Valid) {
            update { it.copy(status = "PAYOUT TARGET REQUIRED", ticket = it.ticket + 1) }
            return false
        }
        val endpoint = settings.endpointOrNull()
        if (endpoint == null) {
            update { it.copy(status = "ENDPOINT INVALID", ticket = it.ticket + 1) }
            return false
        }
        tracker.resetSession()
        stream.clear()
        sessionExtranonce1 = null
        pendingJob = null
        extranonce2Counter = 0
        engineRunning = false
        thermalGuard.onUserTurbo(settings.turbo)
        thermalMonitor.start()
        update {
            it.copy(
                mining = true,
                endpoint = endpoint,
                connection = ConnectionState.CONNECTING,
                connectionDetail = "${endpoint.label} ${endpoint.display} | CONNECTING",
                status = "CONNECTING",
                stream = emptyList(),
                sessionBest = null,
                accepted = 0,
                rejected = 0,
                notices = listOf("CONNECTING TO ${endpoint.display}"),
                ticket = it.ticket + 1,
            )
        }
        workSource.connect(endpoint, settings.workerName(), settings.password())
        return true
    }

    fun stopMining() {
        engineRunning = false
        engine.stop()
        workSource.disconnect()
        thermalMonitor.stop()
        update {
            it.copy(
                mining = false,
                connection = ConnectionState.DISCONNECTED,
                connectionDetail = "OFFLINE",
                status = "STOPPED",
                notices = (listOf("MINER STOPPED") + it.notices).take(MAX_NOTICES),
                ticket = it.ticket + 1,
            )
        }
    }

    /**
     * The design runs the miner only while the terminal is on screen. When the
     * activity stops, the session is closed and no hash is computed in the
     * background; the user starts again with the same button.
     */
    fun onScreenHidden() {
        if (!_state.value.mining) return
        stopMining()
        update {
            it.copy(
                status = "STOPPED (SCREEN OFF)",
                notices = (listOf("SESSION CLOSED: SCREEN OFF") + it.notices).take(MAX_NOTICES),
                ticket = it.ticket + 1,
            )
        }
    }

    /** Resets the expiring thermal guard so the user can try TURBO again. */
    fun clearThermalGuard() {
        thermalGuard.reset()
        update {
            it.copy(
                thermalGuard = false,
                notices = (listOf("THERMAL GUARD RESET") + it.notices).take(MAX_NOTICES),
                ticket = it.ticket + 1,
            )
        }
    }

    fun setTurbo(enabled: Boolean) {
        val applied = thermalGuard.onUserTurbo(enabled)
        if (enabled && !applied) {
            update {
                it.copy(
                    status = "THERMAL GUARD",
                    notices = (listOf("THERMAL GUARD: RESET BEFORE TURBO") + it.notices)
                        .take(MAX_NOTICES),
                    ticket = it.ticket + 1,
                )
            }
            return
        }
        val settings = _state.value.settings.withTurbo(enabled)
        settingsStore.save(settings)
        update {
            it.copy(
                settings = settings,
                thermalGuard = if (enabled) false else it.thermalGuard,
                notices = (listOf("TURBO ${if (enabled) "ON" else "OFF"}") + it.notices).take(MAX_NOTICES),
                ticket = it.ticket + 1,
            )
        }
    }

    fun applySettings(newSettings: MinerSettings) {
        val rawAddress = newSettings.payoutAddress
        val extracted = com.corejbh.hashes.crypto.BitcoinUri.extractAddress(rawAddress) ?: rawAddress
        val requested = newSettings.withAddress(extracted)
        /* A TURBO request is refused while the thermal guard is tripped; the
         * saved setting follows the engine, not the tap. */
        val normalized = if (!thermalGuard.onUserTurbo(requested.turbo)) {
            requested.withTurbo(false)
        } else {
            requested
        }
        settingsStore.save(normalized)
        update {
            it.copy(
                settings = normalized,
                endpoint = normalized.endpointOrNull(),
                thermalGuard = if (normalized.turbo) false else it.thermalGuard,
                notices = (listOf("SETTINGS SAVED") + it.notices).take(MAX_NOTICES),
                ticket = it.ticket + 1,
            )
        }
        if (_state.value.mining) {
            // A changed endpoint or address needs a fresh session; a changed
            // turbo flag was applied to the running engine already.
            stopMining()
            startMining()
        }
    }

    // ------------------------------------------------------------ work events

    private fun observeWorkEvents() {
        viewModelScope.launch {
            workSource.events.collect { event -> handleEvent(event) }
        }
    }

    private fun handleEvent(event: WorkEvent) {
        when (event) {
            is WorkEvent.Connected -> update {
                it.copy(
                    connectionDetail = "${event.endpoint.label} ${event.endpoint.display} | HANDSHAKE",
                    connection = ConnectionState.HANDSHAKE,
                    status = "HANDSHAKE",
                )
            }

            is WorkEvent.SessionStarted -> {
                sessionExtranonce1 = event.extranonce1
                sessionExtranonce2Size = event.extranonce2Size
                update {
                    it.copy(
                        connection = ConnectionState.CONNECTED,
                        connectionDetail = "${it.endpoint?.label ?: "STRATUM"} ${it.endpoint?.display ?: ""} | OK",
                        status = "WAITING FOR JOB",
                        notices = (listOf("AUTHORIZED ${event.user}") + it.notices).take(MAX_NOTICES),
                    )
                }
                pendingJob?.let(::startJob)
            }

            is WorkEvent.Job -> {
                pendingJob = event.job
                startJob(event.job)
            }

            is WorkEvent.Difficulty -> {
                moduleDifficulty = event.difficulty
                update {
                    it.copy(
                        shareDifficulty = event.difficulty,
                        notices = (listOf("DIFFICULTY ${event.difficulty}") + it.notices).take(MAX_NOTICES),
                    )
                }
            }

            is WorkEvent.ExtranonceChanged -> {
                sessionExtranonce1 = event.extranonce1
                sessionExtranonce2Size = event.extranonce2Size
            }

            is WorkEvent.ShareAccepted -> update {
                it.copy(
                    accepted = it.accepted + 1,
                    status = "SHARE ACCEPTED",
                    notices = (listOf("SHARE ACCEPTED ${shortNonce(event.nonce)}") + it.notices).take(MAX_NOTICES),
                    ticket = it.ticket + 1,
                )
            }

            is WorkEvent.ShareRejected -> update {
                it.copy(
                    rejected = it.rejected + 1,
                    status = "SHARE REJECTED",
                    notices = (listOf("SHARE REJECTED ${shortNonce(event.nonce)}: ${event.reason}") + it.notices)
                        .take(MAX_NOTICES),
                    ticket = it.ticket + 1,
                )
            }

            is WorkEvent.Disconnected -> {
                engineRunning = false
                engine.stop()
                update {
                    it.copy(
                        connection = ConnectionState.DISCONNECTED,
                        connectionDetail = "LOST ${it.endpoint?.display ?: ""}",
                        status = if (event.retryInMillis > 0) {
                            "RECONNECT IN ${event.retryInMillis / 1000}s"
                        } else {
                            event.reason.uppercase()
                        },
                        notices = (listOf("LINK DOWN: ${event.reason}") + it.notices).take(MAX_NOTICES),
                        ticket = it.ticket + 1,
                    )
                }
            }

            is WorkEvent.Notice -> update {
                it.copy(notices = (listOf(event.text) + it.notices).take(MAX_NOTICES))
            }
        }
    }

    /**
     * Assembles the next job into bytes and hands it to the engine. A new
     * extranonce2 is chosen for every job so two jobs never share a coinbase.
     */
    private fun startJob(job: MiningJob) {
        val extranonce1 = sessionExtranonce1 ?: return
        if (!_state.value.mining) return
        extranonce2Counter = (extranonce2Counter + 1) and 0xffffffffL
        val extranonce2 = BlockAssembler.extranonce2Hex(extranonce2Counter, sessionExtranonce2Size)
        val prefix = BlockAssembler.headerPrefix(job, extranonce1, extranonce2)
        val target = Target.fromCompact(job.networkBits)
        targetValue = Target.difficulty(job.networkBits)
        val coinbase = BlockAssembler.coinbase(job.coinbase1, extranonce1, extranonce2, job.coinbase2)
        val height = CoinbaseHeight.parse(coinbase)
        val work = HashWork(
            jobId = job.jobId,
            headerPrefix = prefix,
            target = Target.toBytes(target),
            nonceStart = 0,
            networkTime = job.networkTime,
            extranonce2 = extranonce2,
            difficulty = moduleDifficulty,
        )
        if (engineRunning) {
            engine.updateWork(work)
        } else {
            engine.start(work)
            engineRunning = true
        }
        update {
            it.copy(
                job = job,
                blockHeight = height,
                networkBits = job.networkBits,
                networkDifficulty = targetValue,
                status = if (it.mining) "MINING" else it.status,
                notices = (listOf("JOB ${job.jobId} ${if (job.cleanJobs) "CLEAN" else "APPEND"}") + it.notices)
                    .take(MAX_NOTICES),
                connection = ConnectionState.CONNECTED,
                ticket = it.ticket + 1,
            )
        }
    }

    // ------------------------------------------------------------- frame loop

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var frame = 0L
            while (isActive) {
                val current = _state.value
                val snapshot = engine.snapshot()
                if (!current.mining && frame % IDLE_FRAMES_PER_UPDATE != 0L) {
                    // Nothing is running: poll the temperature occasionally
                    // instead of recomposing the terminal 30 times a second.
                    frame += 1
                    delay(FRAME_MILLIS)
                    continue
                }
                if (snapshot.running || current.mining) {
                    drainFoundShares()
                }
                val hex = snapshot.currentHashHex
                val streamLines = if (snapshot.running && hex != ZERO_HASH) {
                    stream.push(hex)
                } else {
                    current.stream
                }
                if (snapshot.running && hex != ZERO_HASH) {
                    tracker.observe(snapshot.currentHash)
                }
                val expected = targetValue?.let { difficulty ->
                    Target.expectedSeconds(difficulty, snapshot.hashRate)
                } ?: Double.POSITIVE_INFINITY

                if (frame % TEMPERATURE_EVERY_FRAMES == 0L) {
                    /* The OS pushes a thermal change once, so a phone that stays
                     * hot would otherwise report a single SEVERE reading. One
                     * reading a second here is what makes "two consecutive"
                     * mean two seconds rather than two notifications. */
                    if (current.mining) {
                        thermalGuard.onReading(thermalMonitor.current())
                    }
                    update {
                        it.copy(
                            snapshot = snapshot,
                            stream = streamLines,
                            sessionBest = tracker.sessionBest(),
                            allTimeBest = tracker.allTimeBest(),
                            expectedSeconds = expected,
                            temperatureCelsius = readTemperature() ?: it.temperatureCelsius,
                        )
                    }
                } else {
                    update {
                        it.copy(
                            snapshot = snapshot,
                            stream = streamLines,
                            sessionBest = tracker.sessionBest(),
                            allTimeBest = tracker.allTimeBest(),
                            expectedSeconds = expected,
                        )
                    }
                }
                frame += 1
                delay(FRAME_MILLIS)
            }
        }
    }

    private fun drainFoundShares() {
        while (true) {
            val found = engine.takeFoundShare() ?: break
            val jitter = "SH-${Hex.encode(found.hash.copyOfRange(0, 4))}"
            workSource.submit(
                ShareSubmission(
                    jobId = found.work.jobId,
                    extranonce2Hex = found.work.extranonce2,
                    networkTime = found.work.networkTime,
                    nonce = found.nonce,
                )
            )
            update {
                it.copy(
                    notices = (listOf("TARGET MET $jitter ${shortNonce(found.nonce)}") + it.notices).take(MAX_NOTICES),
                    ticket = it.ticket + 1,
                )
            }
        }
    }

    /** TURBO steps aside when the device is hot; the guard is visible, not silent. */
    private fun observeThermalGuard() {
        viewModelScope.launch {
            thermalGuard.state.collect { guard ->
                if (!guard.tripped) return@collect
                val settings = _state.value.settings
                if (settings.turbo) {
                    /* Persisted so the next launch starts cool instead of walking
                     * back into the same wall. */
                    settingsStore.save(settings.withTurbo(false))
                }
                update {
                    it.copy(
                        settings = it.settings.withTurbo(false),
                        thermalGuard = true,
                        status = "THERMAL GUARD",
                        notices = (listOf("THERMAL GUARD: TURBO DISABLED") + it.notices)
                            .take(MAX_NOTICES),
                        ticket = it.ticket + 1,
                    )
                }
            }
        }
    }

    private fun readTemperature(): Double? {
        val application = getApplication<Application>()
        val intent: Intent = application.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    override fun onCleared() {
        ticker?.cancel()
        thermalMonitor.stop()
        engine.close()
        workSource.disconnect()
        super.onCleared()
    }

    private fun update(transform: (MinerUiState) -> MinerUiState) {
        _state.value = transform(_state.value)
    }

    private fun shortNonce(nonce: Long): String =
        "0x" + String.format(java.util.Locale.US, "%08X", nonce and 0xffffffffL)

    companion object {
        const val FRAME_MILLIS = 33L
        const val MAX_NOTICES = 4
        const val TEMPERATURE_EVERY_FRAMES = 30L
        const val IDLE_FRAMES_PER_UPDATE = 30L
        private val ZERO_HASH = "0".repeat(64)

        /** Mirrors the native worker limit: one core left for the system, at most eight. */
        fun maxWorkerCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores > 1) minOf(cores - 1, 8) else 1
        }
    }
}
