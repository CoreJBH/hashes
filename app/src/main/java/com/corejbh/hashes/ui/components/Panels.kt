package com.corejbh.hashes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val target = when {
        state.networkBits == null -> stringResource(R.string.label_unset)
        state.networkDifficulty != null ->
            "0x${state.networkBits} / DIFF ${Format.difficulty(state.networkDifficulty)}"
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

/** The scrolling `LIVE_HASH_STREAM`: newest hash first, near misses inverted. */
@Composable
fun HashStreamPanel(state: MinerUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            text = stringResource(R.string.label_live_stream),
            trailing = "[64-HEX]",
        )
        if (state.stream.isEmpty()) {
            repeat(HashStream.DEFAULT_CAPACITY) {
                StreamLine(hex = ZERO_LINE, highlight = false, dim = true)
            }
        } else {
            for (sample in state.stream) {
                StreamLine(hex = sample.hex, highlight = sample.worthHighlighting, dim = false)
            }
            repeat((HashStream.DEFAULT_CAPACITY - state.stream.size).coerceAtLeast(0)) {
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
            style = TerminalTextStyle.copy(fontSize = 8.sp, letterSpacing = 0.sp, color = color),
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
    val odds = if (state.networkDifficulty == null) {
        stringResource(R.string.label_unset)
    } else {
        "1 IN " + Format.hashes(
            com.corejbh.hashes.network.Target.expectedHashes(state.networkDifficulty)
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

/** The last few terminal notices, oldest last. */
@Composable
fun NoticePanel(state: MinerUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (state.notices.isEmpty()) {
            Text(
                text = "> AWAITING COMMAND",
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 10.sp),
            )
        } else {
            for (notice in state.notices) {
                Text(
                    text = "> $notice",
                    style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
                    maxLines = 2,
                )
            }
        }
    }
}
