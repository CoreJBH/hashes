package com.corejbh.hashes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.crypto.BitcoinUri
import com.corejbh.hashes.data.MinerSettings
import com.corejbh.hashes.network.PoolEndpoint
import com.corejbh.hashes.network.PoolPreset
import com.corejbh.hashes.ui.MinerUiState
import com.corejbh.hashes.ui.theme.Crt

/**
 * Pool and payout configuration, drawn as a terminal sheet: preset rows, the
 * payout prompt with paste and QR, and a custom endpoint line. Nothing is applied
 * until [onApply], so a mistyped address never reaches the pool.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: MinerUiState,
    scannedValue: String?,
    onScannedHandled: () -> Unit,
    onScanRequest: () -> Unit,
    onApply: (MinerSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var address by rememberSaveable(state.settings.payoutAddress) {
        mutableStateOf(state.settings.payoutAddress)
    }
    var presetId by rememberSaveable(state.settings.presetId) { mutableStateOf(state.settings.presetId) }
    var customEndpoint by rememberSaveable(state.settings.customEndpoint) {
        mutableStateOf(state.settings.customEndpoint)
    }

    LaunchedEffect(scannedValue) {
        if (!scannedValue.isNullOrBlank()) {
            address = BitcoinUri.extractAddress(scannedValue) ?: scannedValue.trim()
            onScannedHandled()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Crt.Background,
        contentColor = Crt.Phosphor,
        scrimColor = Color.Black.copy(alpha = 0.78f),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            SectionTitle(stringResource(R.string.settings_title), trailing = "v0.1.0")
            Text(
                text = stringResource(R.string.settings_note),
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 9.sp),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            SectionTitle(stringResource(R.string.pool_endpoint))
            for (preset in PoolPreset.entries) {
                PresetRow(
                    preset = preset,
                    selected = presetId == preset.id,
                    onSelect = {
                        presetId = preset.id
                        if (preset.isCustom && customEndpoint.isEmpty()) customEndpoint = ""
                    },
                )
            }

            if (presetId == PoolPreset.CUSTOM.id) {
                Text(
                    text = stringResource(R.string.custom_endpoint),
                    style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
                val endpointCheck = PoolEndpoint.parse(customEndpoint)
                BasicTextField(
                    value = customEndpoint,
                    onValueChange = { customEndpoint = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Crt.Phosphor,
                    ),
                    cursorBrush = SolidColor(Crt.Phosphor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .border(1.dp, if (endpointCheck != null) Crt.PhosphorDim else Crt.PhosphorFaint)
                        .padding(8.dp),
                    decorationBox = { inner ->
                        if (customEndpoint.isEmpty()) {
                            Text(
                                text = stringResource(R.string.custom_endpoint_hint),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Crt.PhosphorFaint,
                                ),
                            )
                        }
                        inner()
                    },
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TerminalBadge(
                        text = if (endpointCheck != null) "[ENDPOINT_OK]" else "[ENDPOINT_INVALID]",
                        inverted = endpointCheck != null,
                        dim = endpointCheck == null,
                    )
                    Text(
                        text = endpointCheck?.let { it.label + " " + it.display } ?: "STRATUM+SSL:// OR STRATUM+TCP://",
                        style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
                    )
                }
            }

            SectionTitle(
                "PAYOUT TARGET",
                modifier = Modifier.padding(top = 16.dp),
                trailing = "MAINNET",
            )
            AddressField(
                value = address,
                onValueChange = { address = it },
                onScanQr = onScanRequest,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalButton(
                    text = stringResource(R.string.button_apply),
                    inverted = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onApply(
                            state.settings
                                .copy(payoutAddress = address.trim(), presetId = presetId)
                                .copy(
                                    customEndpoint = if (presetId == PoolPreset.CUSTOM.id) {
                                        customEndpoint.trim()
                                    } else {
                                        state.settings.customEndpoint
                                    }
                                )
                        )
                    },
                )
                TerminalButton(
                    text = stringResource(R.string.button_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }

            Text(
                text = stringResource(
                    if (state.snapshot.engineName == "native") {
                        R.string.settings_engine_native
                    } else {
                        R.string.settings_engine_fallback
                    }
                ),
                style = TerminalTextStyle.copy(color = Crt.PhosphorFaint, fontSize = 9.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun PresetRow(preset: PoolPreset, selected: Boolean, onSelect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(1.dp, if (selected) Crt.Phosphor else Crt.PhosphorFaint)
            .background(if (selected) Crt.InverseBackground.copy(alpha = 0.08f) else Crt.Background)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (selected) "[x]" else "[ ]",
                style = TerminalTextStyle.copy(
                    color = Crt.Phosphor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = preset.title,
                style = TerminalTextStyle.copy(
                    color = Crt.Phosphor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = if (preset.isCustom) preset.summary else preset.endpoint.url + "  " + preset.summary,
            style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 9.sp),
            modifier = Modifier.padding(start = 18.dp, top = 1.dp),
        )
    }
}
