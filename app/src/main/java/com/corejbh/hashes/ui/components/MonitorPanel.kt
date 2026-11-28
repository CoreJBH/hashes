package com.corejbh.hashes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.ui.MinerUiState
import com.corejbh.hashes.ui.Sparkline
import com.corejbh.hashes.ui.WorkerMeter
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.util.Format
import com.corejbh.hashes.util.ModeLabels
import java.util.Locale

/**
 * The `htop` half of the terminal: one bar per worker thread, and the two live
 * graphs a miner actually watches.
 *
 * Every number here is a difference of two readings the core published, taken a
 * second apart; a worker that hashed nothing shows an empty bar rather than a
 * share of the total. The header carries the thread count, with `TURBO` in front
 * of it only while TURBO runs and `GUARD` once the heat took TURBO away.
 */
@Composable
fun MonitorPanel(state: MinerUiState, modifier: Modifier = Modifier) {
    val rates = state.workerRates
    val workers = state.workerCount
    val peak = rates.maxOrNull() ?: 0.0
    val running = state.mining && workers > 0

    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            text = stringResource(R.string.label_workers),
            trailing = ModeLabels.workerTrailer(
                turbo = state.settings.turbo,
                guard = state.thermalGuard,
                threads = state.snapshot.threads,
                capped = state.thermalCapped,
                threadCap = state.threadCap,
            ),
        )
        if (!running) {
            Text(
                text = "> ${stringResource(R.string.label_workers_idle)}",
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 9.sp),
                maxLines = 1,
            )
        } else {
            for (index in 0 until workers) {
                WorkerRow(
                    index = index,
                    rate = rates.getOrElse(index) { 0.0 },
                    peak = peak,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.label_rate_graph),
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
                maxLines = 1,
            )
            Text(
                text = Sparkline.render(state.rateHistory),
                style = TerminalTextStyle.copy(
                    color = Crt.Phosphor,
                    fontSize = 9.sp,
                    letterSpacing = 0.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = Format.hashRate(state.snapshot.hashRate),
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.label_temp_graph),
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
                maxLines = 1,
            )
            Text(
                text = Sparkline.render(state.temperatureHistory),
                style = TerminalTextStyle.copy(
                    color = Crt.PhosphorDim,
                    fontSize = 9.sp,
                    letterSpacing = 0.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            val degrees = state.temperatureCelsius
                ?.let { String.format(Locale.US, "%.0f", it) + "C" }
                ?: "--"
            Text(
                text = degrees,
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WorkerRow(index: Int, rate: Double, peak: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "T$index",
            style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
            maxLines = 1,
        )
        Text(
            text = WorkerMeter.bar(rate, peak),
            style = TerminalTextStyle.copy(
                color = if (rate > 0.0) Crt.Phosphor else Crt.PhosphorFaint,
                fontSize = 9.sp,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
        )
        Text(
            text = WorkerMeter.label(rate) + " " + stringResource(R.string.unit_hashes_per_second),
            style = TerminalTextStyle.copy(
                color = if (rate > 0.0) Crt.PhosphorDim else Crt.PhosphorFaint,
                fontSize = 9.sp,
            ),
            maxLines = 1,
        )
    }
}
