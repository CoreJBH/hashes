package com.corejbh.hashes.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the mining work comes from. */
enum class ConnectionState { DISCONNECTED, CONNECTING, HANDSHAKE, CONNECTED }

/** A found hash that should be submitted to the pool. */
data class ShareSubmission(
    val jobId: String,
    val extranonce2Hex: String,
    val networkTime: String,
    val nonce: Long,
    val versionBits: String? = null,
)

sealed interface WorkEvent {
    data class Connected(val endpoint: PoolEndpoint) : WorkEvent
    data class SessionStarted(
        val user: String,
        val extranonce1: String,
        val extranonce2Size: Int,
    ) : WorkEvent

    data class Job(val job: MiningJob) : WorkEvent
    data class Difficulty(val difficulty: Double) : WorkEvent
    data class ExtranonceChanged(val extranonce1: String, val extranonce2Size: Int) : WorkEvent
    data class ShareAccepted(val jobId: String, val nonce: Long) : WorkEvent
    data class ShareRejected(val jobId: String, val nonce: Long, val reason: String) : WorkEvent
    data class Disconnected(val reason: String, val retryInMillis: Long) : WorkEvent
    data class Notice(val text: String) : WorkEvent
}

/**
 * A source of mining work.
 *
 * Stratum V1 is the only implementation in v1, but the UI and the hash engine
 * talk to this interface, never to the socket. When PocketCore can hand out a
 * local `getblocktemplate` / `submitblock` RPC, that source implements the same
 * four methods and nothing above the network layer changes.
 */
interface MiningWorkSource {
    val events: Flow<WorkEvent>
    val state: StateFlow<ConnectionState>

    fun connect(endpoint: PoolEndpoint, user: String, password: String)
    fun disconnect()
    fun submit(share: ShareSubmission)

    val isConnected: Boolean
}
