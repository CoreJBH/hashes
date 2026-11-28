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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.ui.MinerUiState
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.util.ModeLabels

/**
 * The top of the terminal: the command line that started the miner, the pool the
 * line is attached to, and the temperature badge. The default mode is not named;
 * the badge grows a `TURBO` only while TURBO runs, and no badge is drawn when
 * there is neither TURBO nor a reading to show.
 */
@Composable
fun HeaderPanel(state: MinerUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.terminal_prompt),
                style = TerminalTextStyle.copy(
                    color = Crt.PhosphorDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            BlinkingCursor()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.connectionDetail,
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
                maxLines = 1,
            )
            val badge = ModeLabels.thermalBadge(state.settings.turbo, state.temperatureCelsius)
            if (badge.isNotEmpty()) {
                TerminalBadge(
                    text = badge,
                    inverted = state.settings.turbo,
                    dim = !state.settings.turbo,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (state.snapshot.hardwareSha256) "SHA2-HW ACTIVE" else "SHA2-SW PATH",
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 9.sp),
            )
            Text(
                text = "ENGINE " + state.snapshot.engineName.uppercase(),
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 9.sp),
            )
        }
    }
}
