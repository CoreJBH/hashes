package com.corejbh.hashes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.ui.components.BestSharePanel
import com.corejbh.hashes.ui.components.BlockPanel
import com.corejbh.hashes.ui.components.EventLogPanel
import com.corejbh.hashes.ui.components.HashStreamPanel
import com.corejbh.hashes.ui.components.HeaderPanel
import com.corejbh.hashes.ui.components.MonitorPanel
import com.corejbh.hashes.ui.components.TelemetryPanel
import com.corejbh.hashes.ui.components.TerminalButton
import com.corejbh.hashes.ui.components.TerminalDivider
import com.corejbh.hashes.ui.components.TerminalTextStyle
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.ui.theme.crtScanlines

/**
 * The main terminal.
 *
 * The screen is a full-height column, not a short list: the session facts and the
 * hash stream at the top, the worker monitor and the event log in the middle, and
 * the TURBO line and the two buttons pinned to the bottom edge. The stream and
 * the log take the space the fixed rows leave (`weight`), so no part of the screen
 * is spent on emptiness at any height.
 *
 * A short screen — landscape, a split view, a small phone — switches to a
 * scrolling column with a fixed stream and log height instead: nothing is ever
 * cut off, and the controls still come last.
 */
@Composable
fun MinerScreen(
    state: MinerUiState,
    onToggleMining: () -> Unit,
    onTurboChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onResetGuard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Crt.Background)
            .crtScanlines()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullHeight = maxHeight >= FULL_LAYOUT_MIN_HEIGHT
            val inset = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 16.dp)
            if (fullHeight) {
                Column(inset) {
                    TerminalBody(
                        state = state,
                        onToggleMining = onToggleMining,
                        onTurboChange = onTurboChange,
                        onOpenSettings = onOpenSettings,
                        onResetGuard = onResetGuard,
                        // These two own the leftover space, and nothing else does.
                        streamModifier = Modifier.weight(1f),
                        logModifier = Modifier.weight(LOG_WEIGHT),
                    )
                }
            } else {
                Column(inset.verticalScroll(rememberScrollState())) {
                    TerminalBody(
                        state = state,
                        onToggleMining = onToggleMining,
                        onTurboChange = onTurboChange,
                        onOpenSettings = onOpenSettings,
                        onResetGuard = onResetGuard,
                        streamModifier = Modifier,
                        logModifier = Modifier,
                        streamLines = COMPACT_STREAM_LINES,
                        logLines = COMPACT_LOG_LINES,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("ModifierParameter")
private fun TerminalBody(
    state: MinerUiState,
    onToggleMining: () -> Unit,
    onTurboChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onResetGuard: () -> Unit,
    // Two flexible slots, not one trailing modifier: the stream and the log take
    // the leftover height, and everything else keeps its measured size.
    streamModifier: Modifier,
    logModifier: Modifier,
    streamLines: Int? = null,
    logLines: Int? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        HeaderPanel(state)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        BlockPanel(state)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        HashStreamPanel(state, streamModifier, fixedLines = streamLines)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        BestSharePanel(state)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        TelemetryPanel(state)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        MonitorPanel(state)
        TerminalDivider(Modifier.padding(vertical = 8.dp))

        EventLogPanel(state, logModifier, fixedLines = logLines)

        // The controls are the bottom edge of the screen, at every size.
        Column(Modifier.padding(top = 10.dp)) {
            TurboLine(
                enabled = state.settings.turbo,
                onChange = onTurboChange,
            )
            if (state.thermalGuard) {
                TerminalButton(
                    text = stringResource(R.string.button_reset_guard),
                    onClick = onResetGuard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TerminalButton(
                    text = stringResource(R.string.button_start_stop),
                    inverted = state.mining,
                    modifier = Modifier.weight(1.35f),
                    onClick = onToggleMining,
                )
                TerminalButton(
                    text = stringResource(R.string.button_pool_payout),
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun TurboLine(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "[x]" else "[ ]",
            style = TerminalTextStyle.copy(
                color = Crt.Phosphor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = stringResource(R.string.turbo_mode),
            style = TerminalTextStyle.copy(
                color = if (enabled) Crt.Phosphor else Crt.PhosphorDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Below this height the weighted layout would leave the panels too small to read. */
private val FULL_LAYOUT_MIN_HEIGHT = 600.dp

/** The log is the smaller of the two flexible panels; the stream stays dominant. */
private const val LOG_WEIGHT = 0.45f

private const val COMPACT_STREAM_LINES = 10
private const val COMPACT_LOG_LINES = 4
