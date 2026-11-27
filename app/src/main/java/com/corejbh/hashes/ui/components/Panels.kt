package com.corejbh.hashes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.data.BestShareRecord
import com.corejbh.hashes.ui.HashStream
import com.corejbh.hashes.ui.MinerUiState
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.util.Format

/** Block, target and coinbase facts of the job being hashed. */
@Composable
fun BlockPanel(state: MinerUiState, modifier: Modifier = Modifier) {
    val job = state.job
    val height = state.blockHeight?.let { Format.count(it) } ?: stringResource(R.string.label_unset)
    val previous = job?.previousHash?.reversed()?.let { Format.abbreviate(it, 12, 8) }
        ?: stringResource(R.string.label_unset)
    val payout = if (state.payoutAddress.isEmpty()) {
        stringResource(R.string.label_unset)
    } else {
        Format.abbreviate(state.payoutAddress, 12, 8)
    }
    val difficulty = state.networkDifficulty
    val target = when {
        state.networkBits == null -> stringResource(R.string.label_unset)
        difficulty != null ->
            "0x${state.networkBits} / DIFF ${Format.difficulty(difficulty)}"
        else -> "0x${state.networkBits}"
    }
    val nonce = Format.nonce(state.snapshot.nonce)

    Column(modifier.fillMaxWidth()) {
        SectionTitle("BLOCK HEADER", trailing = "MAINNET")
        TerminalRow(stringResource(R.string.label_block_height), height)
        TerminalRow(stringResource(R.string.label_prev_hash), previous)
        TerminalRow(stringResource(R.string.label_payout_addr), payout)
        TerminalRow(stringResource(R.string.label_net_target), target)
        TerminalRow(stringResource(R.string.label_nonce), nonce)
        TerminalDivider(Modifier.padding(vertical = 4.dp))
        SectionTitle(stringResource(R.string.label_sha_output))
        Text(
            text = state.snapshot.currentHashHex,
            style = TerminalTextStyle.copy(fontSize = 10.sp, color = Crt.Phosphor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The scrolling `LIVE_HASH_STREAM`: newest hash first, near misses inverted.
 *
 * The panel takes whatever height the screen gives it and draws as many lines as
 * fit there, so the same composable fills the space on a tall phone and shrinks
 * to a few lines on a short one without either a fixed row count or a hard cut.
 */
@Composable
fun HashStreamPanel(
    state: MinerUiState,
    modifier: Modifier = Modifier,
    fixedLines: Int? = null,
) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            text = stringResource(R.string.label_live_stream),
            trailing = "[64-HEX]",
        )
        if (fixedLines != null) {
            StreamLines(state.stream, fixedLines)
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val lineHeight = with(LocalDensity.current) { STREAM_LINE_HEIGHT.toDp() }
                val lines = (maxHeight / lineHeight).toInt().coerceAtLeast(MIN_STREAM_LINES)
                StreamLines(state.stream, lines)
            }
        }
    }
}

@Composable
private fun StreamLines(samples: List<HashStream.Sample>, lines: Int) {
    Column(Modifier.fillMaxWidth()) {
        for (index in 0 until lines) {
            val sample = samples.getOrNull(index)
            if (sample != null) {
                StreamLine(hex = sample.hex, highlight = sample.worthHighlighting, dim = false)
            } else {
                StreamLine(hex = ZERO_LINE, highlight = false, dim = true)
            }
        }
    }
}

@Composable
private fun StreamLine(hex: String, highlight: Boolean, dim: Boolean) {
    val background = if (highlight) Crt.InverseBackground else Crt.Background
    val color = when {
        highlight -> Crt.InverseText
        dim -> Crt.PhosphorFaint
        else -> Crt.PhosphorDim
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(background)
    ) {
        Text(
            text = hex,
            style = TerminalTextStyle.copy(
                fontSize = 8.sp,
                lineHeight = STREAM_LINE_HEIGHT,
                letterSpacing = 0.sp,
                color = color,
            ),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}

/** Best shares: this session and the record on this device. */
@Composable
fun BestSharePanel(state: MinerUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        TerminalRow(
            label = stringResource(R.string.label_session_best),
            value = describe(state.sessionBest),
        )
        TerminalRow(
            label = stringResource(R.string.label_all_time_best),
            value = describe(state.allTimeBest),
            valueColor = Crt.Phosphor,
        )
    }
}

private fun describe(record: BestShareRecord?): String {
    if (record == null) return "NONE YET"
    return "DIFF " + Format.difficulty(record.difficulty) + " / " + Format.abbreviate(record.hashHex, 6, 6)
}

/** HASHRATE, TRIED, ODDS and STATUS in two terminal columns. */
@Composable
fun TelemetryPanel(state: MinerUiState, modifier: Modifier = Modifier) {
    val difficulty = state.networkDifficulty
    val odds = if (difficulty == null) {
        stringResource(R.string.label_unset)
    } else {
        "1 IN " + Format.hashes(
            com.corejbh.hashes.network.Target.expectedHashes(difficulty)
        ) + " (" + Format.duration(state.expectedSeconds) + ")"
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TerminalRow(
                label = stringResource(R.string.label_hashrate),
                value = Format.hashRate(state.snapshot.hashRate),
                modifier = Modifier.weight(1f),
                labelWidth = 10,
            )
            TerminalRow(
                label = stringResource(R.string.label_tried),
                value = Format.count(state.snapshot.hashesTried),
                modifier = Modifier.weight(1f),
                labelWidth = 10,
            )
        }
        TerminalRow(stringResource(R.string.label_odds), odds)
        TerminalRow(
            label = stringResource(R.string.label_status),
            value = statusLine(state),
            valueColor = if (state.mining) Crt.Phosphor else Crt.PhosphorDim,
            labelWidth = 10,
        )
    }
}

private fun statusLine(state: MinerUiState): String {
    val sessions = "A:${state.accepted} R:${state.rejected}"
    val threads = if (state.snapshot.threads > 0) "${state.snapshot.threads}T" else "-"
    return "${state.status} / $threads / $sessions"
}

private const val ZERO_LINE = "0000000000000000000000000000000000000000000000000000000000000000"

/** A hash line is drawn at 8 sp; the reader measures the panel in these. */
val STREAM_LINE_HEIGHT = 11.sp
private const val MIN_STREAM_LINES = 4

/** One event-log line, including the `> ` prompt. */
val LOG_LINE_HEIGHT = 13.sp
private const val MIN_LOG_LINES = 2

/**
 * The event log: the last notices, newest first, filling the space the layout
 * gives it. Lines past the newest notice carry the prompt alone, so the panel
 * reads as a terminal with an empty tail rather than a hole in the screen.
 */
@Composable
fun EventLogPanel(
    state: MinerUiState,
    modifier: Modifier = Modifier,
    fixedLines: Int? = null,
) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            text = stringResource(R.string.label_event_log),
            trailing = "[${state.notices.size}]",
        )
        if (fixedLines != null) {
            LogLines(state.notices, fixedLines)
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val lineHeight = with(LocalDensity.current) { LOG_LINE_HEIGHT.toDp() }
                val lines = (maxHeight / lineHeight).toInt().coerceAtLeast(MIN_LOG_LINES)
                LogLines(state.notices, lines)
            }
        }
    }
}

@Composable
private fun LogLines(notices: List<String>, lines: Int) {
    Column(Modifier.fillMaxWidth()) {
        if (notices.isEmpty()) {
            LogLine("> " + stringResource(R.string.label_awaiting_command), dim = true)
        } else {
            for (index in 0 until lines) {
                val notice = notices.getOrNull(index)
                if (notice != null) {
                    LogLine("> $notice", dim = false)
                } else {
                    LogLine(">", dim = true)
                }
            }
        }
    }
}

@Composable
private fun LogLine(text: String, dim: Boolean) {
    Text(
        text = text,
        style = TerminalTextStyle.copy(
            color = if (dim) Crt.PhosphorFaint else Crt.PhosphorDim,
            fontSize = 10.sp,
            lineHeight = LOG_LINE_HEIGHT,
        ),
        maxLines = 1,
    )
}
