package com.corejbh.hashes.network

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stratum V1 client for solo pools.
 *
 * Life cycle of one connection: dial (TLS first when the endpoint says so),
 * `mining.subscribe`, `mining.authorize`, then a read loop that turns
 * `mining.notify` / `mining.set_difficulty` / `mining.set_extranonce` and share
 * replies into [WorkEvent]s. A dropped or silent connection is retried with the
 * 2 s -> 60 s exponential backoff; the socket is never re-dialled in a tight
 * loop and no share is ever invented to look busy.
 *
 * Idleness: a solo pool sends a new job when a block is found, so ten quiet
 * minutes is normal. A connection that has been silent for [idleReconnectMs]
 * (and a handshake that has not finished in [handshakeTimeoutMs]) is closed and
 * redialled - a phone that changed network otherwise keeps a dead socket
 * forever, because TCP keepalive alone takes hours to notice.
 */
class StratumClient(
    private val transportFactory: (PoolEndpoint) -> StratumTransport = { SocketStratumTransport(it) },
    private val backoff: Backoff = Backoff(),
    private val handshakeTimeoutMs: Long = 25_000,
    private val idleReconnectMs: Long = 20 * 60 * 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MiningWorkSource {

    private val _events = MutableSharedFlow<WorkEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<WorkEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val nextRequestId = AtomicLong(FIRST_SHARE_ID - 1)
    private val inflight = ConcurrentHashMap<Long, ShareSubmission>()
    private var connection: Job? = null

    @Volatile
    private var transport: StratumTransport? = null

    @Volatile
    private var running = false

    @Volatile
    private var user: String = ""

    @Volatile
    private var lastInbound: Long = 0

    @Volatile
    private var handshaken = false

    override val isConnected: Boolean
        get() = _state.value == ConnectionState.CONNECTED

    override fun connect(endpoint: PoolEndpoint, user: String, password: String) {
        disconnect()
        this.user = user
        running = true
        connection = scope.launch { runConnection(endpoint, user, password) }
    }

    override fun disconnect() {
        running = false
        connection?.cancel()
        connection = null
        closeTransport()
        inflight.clear()
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Sends one share. Called from the UI thread, so the write is taken under the
     * transport's own lock; a share that cannot be written is reported and left
     * unsent, never retried against another job.
     */
    override fun submit(share: ShareSubmission) {
        val transport = this.transport
        if (transport == null || !isConnected) {
            emit(WorkEvent.ShareRejected(share.jobId, share.nonce, "not connected"))
            return
        }
        val id = nextRequestId.incrementAndGet()
        inflight[id] = share
        try {
            transport.writeLine(
                StratumProtocol.submit(
                    id = id,
                    user = user,
                    jobId = share.jobId,
                    extranonce2Hex = share.extranonce2Hex,
                    networkTime = share.networkTime,
                    nonce = share.nonce,
                    versionBits = share.versionBits,
                )
            )
        } catch (error: Exception) {
            inflight.remove(id)
            emit(WorkEvent.ShareRejected(share.jobId, share.nonce, "write failed: ${describe(error)}"))
        }
    }

    private suspend fun runConnection(endpoint: PoolEndpoint, user: String, password: String) {
        backoff.reset()
        while (currentCoroutineContext().isActive && running) {
            _state.value = ConnectionState.CONNECTING
            var current: StratumTransport? = null
            try {
                current = transportFactory(endpoint)
                current.connect()
                transport = current
                handshaken = false
                lastInbound = clock()
                _state.value = ConnectionState.HANDSHAKE
                emit(WorkEvent.Connected(endpoint))

                val watchdog = scope.launch { watch(current, endpoint) }
                try {
                    val session = handshake(current, user, password)
                    handshaken = true
                    current.setReadTimeout(0)
                    _state.value = ConnectionState.CONNECTED
                    backoff.reset()
                    emit(
                        WorkEvent.SessionStarted(
                            user = user,
                            extranonce1 = session.extranonce1,
                            extranonce2Size = session.extranonce2Size,
                        )
                    )
                    while (currentCoroutineContext().isActive && running) {
                        val line = current.readLine() ?: throw IOException("pool closed the connection")
                        lastInbound = clock()
                        dispatch(line)
                    }
                } finally {
                    watchdog.cancel()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = ConnectionState.DISCONNECTED
                if (!running) break
                val wait = backoff.nextDelayMs()
                emit(WorkEvent.Disconnected(describe(error), wait))
                delay(wait)
            } finally {
                current?.close()
                if (transport === current) transport = null
                inflight.clear()
            }
        }
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun handshake(transport: StratumTransport, user: String, password: String): StratumProtocol.SubscribeSession {
        transport.writeLine(StratumProtocol.subscribe(StratumProtocol.ID_SUBSCRIBE, USER_AGENT))
        val subscribe = awaitResponse(transport, StratumProtocol.ID_SUBSCRIBE)
        subscribe.error?.let { throw IOException("subscribe refused: $it") }
        val session = StratumProtocol.subscribeResult(subscribe.result)
        transport.setReadTimeout(handshakeTimeoutMs.toInt())

        transport.writeLine(StratumProtocol.authorize(StratumProtocol.ID_AUTHORIZE, user, password))
        val authorize = awaitResponse(transport, StratumProtocol.ID_AUTHORIZE)
        authorize.error?.let { throw IOException("authorize refused: $it") }
        val accepted = when (val result = authorize.result) {
            is Boolean -> result
            is String -> result.equals("true", ignoreCase = true)
            else -> false
        }
        if (!accepted) throw IOException("authorize rejected for this address")

        // Best effort: pools that implement difficulty suggestions lower the
        // share difficulty for a phone. Pools that ignore it simply keep theirs,
        // which is not an error.
        transport.writeLine(
            StratumProtocol.suggestDifficulty(StratumProtocol.ID_SUGGEST_DIFFICULTY, SUGGESTED_DIFFICULTY)
        )
        return session
    }

    private fun awaitResponse(transport: StratumTransport, id: Long): StratumProtocol.Message.Response {
        while (true) {
            val line = transport.readLine() ?: throw IOException("pool closed the connection during the handshake")
            lastInbound = clock()
            when (val message = parse(line)) {
                is StratumProtocol.Message.Response -> if (message.id == id) return message else handle(message)
                is StratumProtocol.Message -> handle(message)
                null -> Unit
            }
        }
    }

    private fun dispatch(line: String) {
        val message = parse(line) ?: return
        handle(message)
    }

    private fun parse(line: String): StratumProtocol.Message? = try {
        StratumProtocol.parse(line)
    } catch (error: StratumFormatException) {
        emit(WorkEvent.Notice("unusable pool message: ${error.message}"))
        null
    }

    private fun handle(message: StratumProtocol.Message) {
        when (message) {
            is StratumProtocol.Message.Notify -> emit(WorkEvent.Job(message.job))
            is StratumProtocol.Message.SetDifficulty -> emit(WorkEvent.Difficulty(message.difficulty))
            is StratumProtocol.Message.SetExtranonce ->
                emit(WorkEvent.ExtranonceChanged(message.extranonce1, message.extranonce2Size))
            is StratumProtocol.Message.Notice -> emit(
                WorkEvent.Notice(if (message.text.isNullOrEmpty()) message.method else "${message.method}: ${message.text}")
            )
            is StratumProtocol.Message.Response -> resolve(message)
        }
    }

    private fun resolve(response: StratumProtocol.Message.Response) {
        val share = inflight.remove(response.id) ?: run {
            if (response.id < FIRST_SHARE_ID) {
                emit(WorkEvent.Notice("late handshake reply ${response.id}"))
            }
            return
        }
        if (response.error != null) {
            emit(WorkEvent.ShareRejected(share.jobId, share.nonce, response.error.toString()))
            return
        }
        val accepted = when (val result = response.result) {
            is Boolean -> result
            is String -> result.equals("true", ignoreCase = true)
            null -> true
            else -> false
        }
        if (accepted) {
            emit(WorkEvent.ShareAccepted(share.jobId, share.nonce))
        } else {
            emit(WorkEvent.ShareRejected(share.jobId, share.nonce, "pool rejected the share"))
        }
    }

    private suspend fun watch(transport: StratumTransport, endpoint: PoolEndpoint) {
        while (running) {
            delay(WATCHDOG_INTERVAL_MS)
            if (!running) return
            val idle = clock() - lastInbound
            val limit = if (handshaken) idleReconnectMs else handshakeTimeoutMs
            if (idle > limit) {
                emit(
                    WorkEvent.Disconnected(
                        "no traffic from ${endpoint.display} for ${idle / 1000}s",
                        0,
                    )
                )
                transport.close()
                return
            }
        }
    }

    private fun emit(event: WorkEvent) {
        _events.tryEmit(event)
    }

    private fun closeTransport() {
        try {
            transport?.close()
        } catch (_: IOException) {
            // Nothing to report: the connection is being dropped anyway.
        }
        transport = null
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        const val USER_AGENT = "Hashes/0.1.0"
        const val SUGGESTED_DIFFICULTY = 1.0

        /** Share requests get ids far away from the four handshake ids. */
        const val FIRST_SHARE_ID = 100L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }
}
