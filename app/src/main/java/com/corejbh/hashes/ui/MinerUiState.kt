package com.corejbh.hashes.ui

import com.corejbh.hashes.data.BestShareRecord
import com.corejbh.hashes.data.MinerSettings
import com.corejbh.hashes.engine.EngineSnapshot
import com.corejbh.hashes.network.ConnectionState
import com.corejbh.hashes.network.MiningJob
import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.network.Target
import com.corejbh.hashes.session.MiningSessionState
import java.math.BigDecimal

/**
 * What the terminal draws: the session as the service holds it, plus the parts
 * that belong to the screen alone — the hash stream, the per-worker rates and the
 * history graphs. They are filled only while the screen is visible; the session
 * they describe keeps running without them.
 */
data class MinerUiState(
    val session: MiningSessionState,
    /** The engine reading of this frame; the session's own poll is the fallback. */
    val snapshot: EngineSnapshot = session.snapshot,
    val stream: List<HashStream.Sample> = emptyList(),
    val workerRates: List<Double> = emptyList(),
    val rateHistory: List<Double> = emptyList(),
    val temperatureHistory: List<Double> = emptyList(),
) {
    val settings: MinerSettings get() = session.settings
    val endpoint: PoolEndpoint? get() = session.endpoint
    val connection: ConnectionState get() = session.connection
    val connectionDetail: String get() = session.connectionDetail
    val mining: Boolean get() = session.mining
    val job: MiningJob? get() = session.job
    val blockHeight: Long? get() = session.blockHeight
    val networkBits: String? get() = session.networkBits
    val networkDifficulty: BigDecimal? get() = session.networkDifficulty
    val shareDifficulty: Double get() = session.shareDifficulty
    val sessionBest: BestShareRecord? get() = session.sessionBest
    val allTimeBest: BestShareRecord? get() = session.allTimeBest
    val accepted: Int get() = session.accepted
    val rejected: Int get() = session.rejected
    val status: String get() = session.status
    val notices: List<String> get() = session.notices
    val thermalGuard: Boolean get() = session.thermalGuard
    val thermalCapped: Boolean get() = session.thermalCapped
    val threadCap: Int get() = session.threadCap
    val temperatureCelsius: Double? get() = session.temperatureCelsius
    val engineNative: Boolean get() = session.engineNative
    val payoutAddress: String get() = settings.payoutAddress

    /** The worker slots the panel draws: the mode's count, whether or not they run. */
    val workerCount: Int get() = snapshot.workerHashes.size

    /** The honest expected time for the network difficulty at the measured rate. */
    val expectedSeconds: Double
        get() = networkDifficulty?.let { Target.expectedSeconds(it, snapshot.hashRate) }
            ?: Double.POSITIVE_INFINITY
}
