package com.corejbh.hashes.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.crypto.Hex
import com.corejbh.hashes.data.BestShareRecord
import com.corejbh.hashes.data.BestShareTracker
import com.corejbh.hashes.data.MinerSettings
import com.corejbh.hashes.data.SharedPreferencesBestSharePersistence
import com.corejbh.hashes.data.SharedPreferencesSettingsStore
import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.engine.FallbackHashEngine
import com.corejbh.hashes.engine.JniMinerBinding
import com.corejbh.hashes.engine.MiningEngine
import com.corejbh.hashes.engine.NativeMiningEngine
import com.corejbh.hashes.network.ConnectionState
import com.corejbh.hashes.network.MiningJob
import com.corejbh.hashes.network.MiningWorkSource
import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.network.ShareSubmission
import com.corejbh.hashes.network.StratumClient
import com.corejbh.hashes.network.WorkEvent
import com.corejbh.hashes.thermal.ThermalGuard
import com.corejbh.hashes.thermal.ThermalMonitor
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the terminal and the notification draw about the mining session. */
data class MiningSessionState(
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
    val sessionBest: BestShareRecord? = null,
    val allTimeBest: BestShareRecord? = null,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val status: String = "IDLE",
    val notices: List<String> = emptyList(),
    val temperatureCelsius: Double? = null,
    val thermalGuard: Boolean = false,
    val thermalCapped: Boolean = false,
    val threadCap: Int = 1,
    val engineNative: Boolean = false,
)

/**
 * The mining session itself: settings, the work source, the hash engine, the
 * thermal guard and the loop that turns engine counters into state.
 *
 * It is process-wide and independent of any screen. `MinerForegroundService`
 * keeps it running while the app is not on screen, and the terminal only reads
 * it; nothing here knows about Compose, notifications or activities. Both the
 * service and the view model reach the same instance through [get], so the
 * terminal picks up a session that was started before it was opened and the
 * notification keeps showing it after the terminal closes.
 *
 * The selection of engine and work source is made once here, through their
 * interfaces; the UI has no knowledge of JNI or sockets.
 */
class MiningSession private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val settingsStore = SharedPreferencesSettingsStore(appContext)
    private val tracker = BestShareTracker(SharedPreferencesBestSharePersistence(appContext))

    private val engine: MiningEngine = createEngine()
    private val workSource: MiningWorkSource = StratumClient()

    /**
     * The jobs of this session, with the one lock their state lives behind:
     * the extranonce, the extranonce2 counter, the pool difficulty and the work
     * the engine holds. Work events arrive on the collector and the terminal
     * resets that state from its own thread, so none of it is a plain field
     * here any more.
     */
    private val sessionJobs = SessionJobs(engine)

    /** MODERATE halves TURBO's workers, two SEVERE readings take TURBO away. */
    private val thermalGuard = ThermalGuard(engine, maxWorkerCount())

    /** Reads the device's heat and feeds the guard above. */
    private val thermalMonitor = ThermalMonitor(appContext) { decision ->
        thermalGuard.onReading(decision)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        MiningSessionState(
            settings = settingsStore.load(),
            sessionBest = tracker.sessionBest(),
            allTimeBest = tracker.allTimeBest(),
            engineNative = engineIsNative,
        ).let { initial ->
            initial.copy(
                endpoint = initial.settings.endpoint(),
                snapshot = engine.snapshot(),
                threadCap = maxWorkerCount(),
            )
        }
    )
    val state: StateFlow<MiningSessionState> = _state.asStateFlow()

    /** A job that arrived before the pool's session was authorized. */
    @Volatile
    private var pendingJob: MiningJob? = null

    /** The one-second housekeeping loop of a running session. */
    private var ticker: Job? = null

    /**
     * The last start or stop on this session, so the two run in the order they
     * were asked for.
     *
     * Stopping the engine joins worker threads and closing the pool socket is
     * I/O, so a teardown runs off the calling thread: [stop] is reached from the
     * terminal's button and from the service's `onDestroy`, on the main thread.
     * A start queues behind that teardown, so a new session never spins its
     * workers beside the old one's and a late connect cannot outlive a stop.
     */
    @Volatile
    private var lifecycle: Job? = null

    init {
        thermalGuard.onUserTurbo(_state.value.settings.turbo)
        observeWorkEvents()
        observeThermalGuard()
    }

    private fun createEngine(): MiningEngine {
        val binding = JniMinerBinding()
        return if (binding.available) NativeMiningEngine(binding) else FallbackHashEngine()
    }

    private val engineIsNative: Boolean
        get() = engine is NativeMiningEngine && (engine as NativeMiningEngine).isAvailable()

    /** The engine name the settings sheet prints. */
    val engineName: String
        get() = if (engineIsNative) "native" else engine.name

    // ---------------------------------------------------------------- controls

    /** True while a session is running, in the terminal or in the background. */
    val isMining: Boolean
        get() = _state.value.mining

    /** The settings as saved, for a screen that opens on a running session. */
    fun currentSettings(): MinerSettings = _state.value.settings

    /**
     * Starts a session with the saved settings. Returns false when the payout
     * target or the endpoint cannot be used; the terminal then opens the
     * settings sheet instead of a notification.
     */
    fun start(): Boolean {
        if (_state.value.mining) return true
        val settings = settingsStore.load()
        if (BitcoinAddressValidator.validate(settings.payoutAddress) !is BitcoinAddressValidator.Result.Valid) {
            update { it.copy(settings = settings, status = "PAYOUT TARGET REQUIRED") }
            return false
        }
        val endpoint = settings.endpointOrNull()
        if (endpoint == null) {
            update { it.copy(settings = settings, status = "ENDPOINT INVALID") }
            return false
        }
        tracker.resetSession()
        pendingJob = null
        sessionJobs.reset()
        sessionJobs.open()
        thermalGuard.onUserTurbo(settings.turbo)
        thermalMonitor.start()
        update {
            it.copy(
                settings = settings,
                mining = true,
                endpoint = endpoint,
                connection = ConnectionState.CONNECTING,
                connectionDetail = "${endpoint.label} ${endpoint.display} | CONNECTING",
                status = "CONNECTING",
                sessionBest = null,
                accepted = 0,
                rejected = 0,
                notices = listOf("CONNECTING TO ${endpoint.display}"),
            )
        }
        /* Queued behind a teardown that may still be running. If a STOP arrives
         * while this waits, the flag is already down and nothing starts. */
        enqueue {
            if (!_state.value.mining) return@enqueue
            startTicker()
            workSource.connect(endpoint, settings.workerName(), settings.password())
        }
        return true
    }

    /**
     * Stops hashing, closes the pool link and drops the thermal listener.
     *
     * The visible state flips here, on whatever thread pressed STOP; the engine
     * join and the socket close go to an I/O thread, because neither belongs on
     * the main thread - a TLS socket close writes a record, which the platform
     * forbids there.
     */
    fun stop() {
        if (!_state.value.mining) return
        ticker?.cancel()
        ticker = null
        sessionJobs.close()
        thermalMonitor.stop()
        update {
            it.copy(
                mining = false,
                connection = ConnectionState.DISCONNECTED,
                connectionDetail = "OFFLINE",
                status = "STOPPED",
                notices = (listOf("MINER STOPPED") + it.notices).take(MAX_NOTICES),
            )
        }
        enqueue {
            withContext(Dispatchers.IO) {
                engine.stop()
                workSource.disconnect()
            }
        }
    }

    /** Runs [block] after the previous start or stop has finished. */
    private fun enqueue(block: suspend () -> Unit) {
        val previous = lifecycle
        lifecycle = scope.launch {
            previous?.join()
            block()
        }
    }

    /** Resets the expiring thermal guard so the user can try TURBO again. */
    fun clearThermalGuard() {
        thermalGuard.reset()
        update {
            it.copy(
                thermalGuard = false,
                notices = (listOf("THERMAL GUARD RESET") + it.notices).take(MAX_NOTICES),
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
            )
        }
        if (_state.value.mining) {
            // A changed endpoint or address needs a fresh session; a changed
            // turbo flag was applied to the running engine already.
            stop()
            start()
        }
    }

    /** A fresh reading for a screen frame; the session loop polls on its own too. */
    fun sampleEngine(): EngineSnapshot = engine.snapshot()

    // ------------------------------------------------------------ work events

    private fun observeWorkEvents() {
        scope.launch {
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
                sessionJobs.onExtranonce(event.extranonce1, event.extranonce2Size)
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
                /* Applied to the running job straight away, so the engine stops
                 * mining against the network target as soon as the pool says
                 * what it really wants. The engine's swap clears its found-hash
                 * queue, so a share it already reported is sent first. */
                drainFoundShares()
                val retargeted = sessionJobs.onDifficulty(event.difficulty)
                val note = "DIFFICULTY ${event.difficulty}" + if (retargeted) " RETARGET" else ""
                update {
                    it.copy(
                        shareDifficulty = event.difficulty,
                        notices = (listOf(note) + it.notices).take(MAX_NOTICES),
                    )
                }
            }

            is WorkEvent.ExtranonceChanged -> {
                sessionJobs.onExtranonce(event.extranonce1, event.extranonce2Size)
            }

            is WorkEvent.ShareAccepted -> update {
                it.copy(
                    accepted = it.accepted + 1,
                    status = "SHARE ACCEPTED",
                    notices = (listOf("SHARE ACCEPTED ${shortNonce(event.nonce)}") + it.notices).take(MAX_NOTICES),
                )
            }

            is WorkEvent.ShareRejected -> update {
                it.copy(
                    rejected = it.rejected + 1,
                    status = "SHARE REJECTED",
                    notices = (listOf("SHARE REJECTED ${shortNonce(event.nonce)}: ${event.reason}") + it.notices)
                        .take(MAX_NOTICES),
                )
            }

            is WorkEvent.Disconnected -> {
                sessionJobs.stopEngine()
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
                    )
                }
            }

            is WorkEvent.Notice -> update {
                it.copy(notices = (listOf(event.text) + it.notices).take(MAX_NOTICES))
            }
        }
    }

    /**
     * Hands the next job to the engine.
     *
     * [SessionJobs] assembles the header and picks a new extranonce2, so two jobs
     * never share a coinbase, and gives the engine the pool's share target rather
     * than the network one. What is drawn here stays the network's: `NET_TARGET`
     * and the difficulty of the job's compact target are the block's, not the
     * pool's, and the odds line is about finding a block.
     */
    private fun startJob(job: MiningJob) {
        if (!_state.value.mining) return
        val started = sessionJobs.startJob(job) ?: return
        update {
            it.copy(
                job = job,
                blockHeight = started.blockHeight,
                networkBits = job.networkBits,
                networkDifficulty = started.networkDifficulty,
                status = if (it.mining) "MINING" else it.status,
                notices = (listOf("JOB ${job.jobId} ${if (job.cleanJobs) "CLEAN" else "APPEND"}") + it.notices)
                    .take(MAX_NOTICES),
                connection = ConnectionState.CONNECTED,
            )
        }
    }

    // --------------------------------------------------------------- session loop

    /**
     * One tick a second while a session runs: the counters the notification and
     * the screen show, the thermal reading the guard needs, and the drain that
     * submits a hash as soon as the engine reports one. It runs with the session,
     * not with the screen, so a found share is submitted from the background too.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val snapshot = engine.snapshot()
                if (snapshot.running) {
                    drainFoundShares()
                    observeHashes(snapshot)
                }
                thermalGuard.onReading(thermalMonitor.current())
                val temperature = readTemperature()
                update {
                    it.copy(
                        snapshot = snapshot,
                        sessionBest = tracker.sessionBest(),
                        allTimeBest = tracker.allTimeBest(),
                        temperatureCelsius = temperature ?: it.temperatureCelsius,
                    )
                }
                delay(HOUSEKEEPING_MILLIS)
            }
        }
    }

    /**
     * Feeds the best-share tracker the digest the engine just finished and, when
     * the core kept one, the best digest of the job.
     *
     * A zeroed buffer is not a hash: the core publishes one until its first
     * digest completes, and the tracker would read it as the best share there
     * can ever be. The guard lives here as well as in the tracker because this
     * loop is what writes the all-time record.
     */
    private fun observeHashes(snapshot: EngineSnapshot) {
        if (snapshot.currentHash.any { it != 0.toByte() }) {
            tracker.observe(snapshot.currentHash)
        }
        val best = snapshot.bestHash
        if (best != null && best.size == 32 && best.any { it != 0.toByte() }) {
            tracker.observe(best)
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
                    notices = (listOf("TARGET MET $jitter ${shortNonce(found.nonce)}") + it.notices)
                        .take(MAX_NOTICES),
                )
            }
        }
    }

    /** TURBO steps aside when the device is hot; the guard is visible, not silent. */
    private fun observeThermalGuard() {
        scope.launch {
            thermalGuard.state.collect { guard ->
                update {
                    it.copy(
                        thermalGuard = guard.tripped || it.thermalGuard,
                        thermalCapped = guard.threadsCapped,
                        threadCap = guard.threadCap,
                    )
                }
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
                    )
                }
            }
        }
    }

    private fun readTemperature(): Double? {
        val intent: Intent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    /**
     * One atomic step for the whole session.
     *
     * The session is written from `Main` (the terminal's buttons, the service's
     * intents) and from `Default` (the work-event collector, the thermal guard,
     * the ticker). `_state.value = transform(_state.value)` is a read-modify-write
     * that loses one of two updates when both sides reach it together, which is
     * how a share counter or the newest notice used to disappear. `update` retries
     * the transform against the value it actually replaced, and every transform
     * here is a pure function of the previous state, so a retry is safe.
     */
    private fun update(transform: (MiningSessionState) -> MiningSessionState) {
        _state.update(transform)
    }

    private fun shortNonce(nonce: Long): String =
        "0x" + String.format(java.util.Locale.US, "%08X", nonce and 0xffffffffL)

    companion object {
        /** Notification and screen both read this often. */
        const val HOUSEKEEPING_MILLIS = 1_000L

        /** The event log the terminal scrolls; the panel shows as many as fit. */
        const val MAX_NOTICES = 24

        @Volatile
        private var instance: MiningSession? = null

        /** The one session of this process. */
        fun get(context: Context): MiningSession {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: MiningSession(context).also { instance = it }
            }
        }

        /** Mirrors the native worker limit: one core left for the system, at most eight. */
        fun maxWorkerCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores > 1) minOf(cores - 1, 8) else 1
        }
    }
}
