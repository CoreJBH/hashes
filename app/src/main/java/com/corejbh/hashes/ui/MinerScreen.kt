package com.corejbh.hashes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * The screen has two parts and only two. The controls — the TURBO line, the
 * thermal reset and the two buttons — are the last child of the outer column and
 * take their measured height first, so no amount of content above them can
 * squeeze them to nothing; they are the one thing that is always on screen. The
 * body above them gets every remaining pixel through `weight(1f, fill = true)`.
 *
 * The body is one scrolling column, so a short screen, a split view, a landscape
 * phone or a large system font size scrolls instead of cutting the last panels
 * off. The two panels that may grow — `LIVE_HASH_STREAM` and `EVENT_LOG` — take
 * what the fixed rows leave: both blocks of fixed panels report their laid-out
 * height, and the stream and the log split the remainder 70/30 with a floor of
 * their own. So on a tall phone the panels fill the screen exactly and nothing
 * scrolls, and when the fixed panels leave too little the floors win and the
 * column scrolls, which is safe at any size.
 *
 * The earlier shape decided this once from `maxHeight >= 600dp`; a 600–700 dp
 * screen at 1.2x font scale fell on the wrong side of that line and gave the
 * buttons zero height. Nothing here compares against a screen height.
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
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            TerminalBody(state)
            TerminalControls(
                state = state,
                onToggleMining = onToggleMining,
                onTurboChange = onTurboChange,
                onOpenSettings = onOpenSettings,
                onResetGuard = onResetGuard,
            )
        }
    }
}

@Composable
private fun ColumnScope.TerminalBody(state: MinerUiState) {
    BoxWithConstraints(
        Modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
    ) {
        val viewport = maxHeight
        // Measured, not assumed: each fixed block reports the height it was laid
        // out at, whatever the font scale does to it.
        var topHeight by remember { mutableStateOf(0.dp) }
        var middleHeight by remember { mutableStateOf(0.dp) }
        val flexible = TerminalLayout.flexibleHeights(
            viewport = viewport,
            fixed = topHeight + middleHeight + TerminalLayout.DIVIDER,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            MeasuredBlock(onHeight = { topHeight = it }) {
                HeaderPanel(state)
                TerminalDivider(Modifier.padding(vertical = 8.dp))
                BlockPanel(state)
                TerminalDivider(Modifier.padding(vertical = 8.dp))
            }

            HashStreamPanel(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(flexible.stream),
            )
            TerminalDivider(Modifier.padding(vertical = 8.dp))

            MeasuredBlock(onHeight = { middleHeight = it }) {
                BestSharePanel(state)
                TerminalDivider(Modifier.padding(vertical = 8.dp))
                TelemetryPanel(state)
                TerminalDivider(Modifier.padding(vertical = 8.dp))
                MonitorPanel(state)
                TerminalDivider(Modifier.padding(vertical = 8.dp))
            }

            EventLogPanel(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(flexible.log),
            )
        }
    }
}

/**
 * A column of fixed rows that tells the layout how tall it came out, so the
 * flexible panels can take exactly what is left.
 */
@Composable
private fun MeasuredBlock(
    onHeight: (Dp) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size -> onHeight(with(density) { size.height.toDp() }) }
    ) {
        content()
    }
}

/** TURBO, the thermal reset when it is armed, and the two buttons. Never compressed. */
@Composable
private fun TerminalControls(
    state: MinerUiState,
    onToggleMining: () -> Unit,
    onTurboChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onResetGuard: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
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

/**
 * How the body's height is divided once the fixed panels have taken theirs.
 *
 * The stream is the panel the terminal is watched for, so it keeps the larger
 * share; the log follows it. Both floors are sizes a panel still reads at, not a
 * bet on the screen: when the fixed panels leave less than the floors, the sum
 * exceeds the viewport and the body scrolls.
 */
internal object TerminalLayout {

    /** One `TerminalDivider` with its 8 dp of air on both sides. */
    val DIVIDER: Dp = 17.dp

    /** A stream panel of four 11 sp lines under its title. */
    val MIN_STREAM: Dp = 66.dp

    /** A log panel of three 13 sp lines under its title. */
    val MIN_LOG: Dp = 50.dp

    private const val STREAM_SHARE = 0.70f
    private const val LOG_SHARE = 0.30f

    data class Flexible(val stream: Dp, val log: Dp)

    fun flexibleHeights(viewport: Dp, fixed: Dp): Flexible {
        val leftover = (viewport - fixed).coerceAtLeast(0.dp)
        return Flexible(
            stream = (leftover * STREAM_SHARE).coerceAtLeast(MIN_STREAM),
            log = (leftover * LOG_SHARE).coerceAtLeast(MIN_LOG),
        )
    }
}
