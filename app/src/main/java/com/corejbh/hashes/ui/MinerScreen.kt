package com.corejbh.hashes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.corejbh.hashes.ui.components.HashStreamPanel
import com.corejbh.hashes.ui.components.HeaderPanel
import com.corejbh.hashes.ui.components.NoticePanel
import com.corejbh.hashes.ui.components.SectionTitle
import com.corejbh.hashes.ui.components.TelemetryPanel
import com.corejbh.hashes.ui.components.TerminalButton
import com.corejbh.hashes.ui.components.TerminalDivider
import com.corejbh.hashes.ui.components.TerminalTextStyle
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.ui.theme.crtScanlines

/**
 * The main terminal. One scrollable column, the whole session visible at once:
 * header, block facts, live hash stream, best shares, telemetry, controls.
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
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .crtScanlines()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 18.dp)
        ) {
            HeaderPanel(state)
            TerminalDivider(Modifier.padding(vertical = 8.dp))

            BlockPanel(state)
            TerminalDivider(Modifier.padding(vertical = 8.dp))

            HashStreamPanel(state)
            TerminalDivider(Modifier.padding(vertical = 8.dp))

            BestSharePanel(state)
            TerminalDivider(Modifier.padding(vertical = 8.dp))

            TelemetryPanel(state)
            TerminalDivider(Modifier.padding(vertical = 8.dp))

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
                    .padding(top = 12.dp),
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

            SkipOne()
            NoticePanel(state)
        }
    }
}

@Composable
private fun TurboLine(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 4.dp),
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

/** A small vertical gap that also keeps the last notice off the gesture bar. */
@Composable
private fun SkipOne() {
    Box(Modifier.padding(top = 10.dp))
}
