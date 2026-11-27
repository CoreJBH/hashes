package com.corejbh.hashes.notification

import com.corejbh.hashes.network.ConnectionState
import com.corejbh.hashes.session.MiningSessionState
import com.corejbh.hashes.util.Format
import java.util.Locale

/**
 * What the status bar says while a session runs, kept apart from the service so
 * the wording is unit-tested without Android: the mode, the pool link, the live
 * hash rate and the block being hashed, all in the app's plain English.
 *
 * The collapsed line carries the two facts a glance needs (`STRATUM+TLS OK ·
 * 367.6 KH/s`); the expanded view adds the mode, the temperature, the height,
 * the worker count and the share tally.
 */
data class MinerNotificationContent(
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
) {
    companion object {

        fun of(state: MiningSessionState): MinerNotificationContent {
            val mode = if (state.settings.turbo) "TURBO" else "COOL"
            val rate = Format.hashRate(state.snapshot.hashRate)
            val link = linkLine(state.connection, state.endpoint?.label ?: "STRATUM")
            val height = state.blockHeight?.let { "BLOCK " + Format.count(it) } ?: "BLOCK --"
            val threads = if (state.snapshot.threads > 0) "${state.snapshot.threads}T" else "--"
            val degrees = state.temperatureCelsius?.let { String.format(Locale.US, "%.0f", it) + "C" }

            val modeLine = buildString {
                append(link)
                append(" \u00b7 ")
                append(mode)
                if (degrees != null) {
                    append(" \u00b7 ")
                    append(degrees)
                }
            }
            val factsLine = "$rate \u00b7 $height \u00b7 $threads"
            val sharesLine = "SHARES A:${state.accepted} R:${state.rejected}"

            return MinerNotificationContent(
                title = "HASHES \u00b7 $mode",
                text = "$link \u00b7 $rate",
                bigText = "$modeLine\n$factsLine\n$sharesLine",
                subText = height,
            )
        }

        /** The pool label carries the transport (`STRATUM+TLS`), so TCP never reads as TLS. */
        private fun linkLine(connection: ConnectionState, label: String): String = when (connection) {
            ConnectionState.CONNECTED -> "$label OK"
            ConnectionState.HANDSHAKE -> "$label HANDSHAKE"
            ConnectionState.CONNECTING -> "$label CONNECTING"
            ConnectionState.DISCONNECTED -> "$label OFFLINE"
        }
    }
}
