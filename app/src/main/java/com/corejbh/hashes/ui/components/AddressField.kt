package com.corejbh.hashes.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.R
import com.corejbh.hashes.crypto.BitcoinAddressValidator
import com.corejbh.hashes.crypto.BitcoinUri
import com.corejbh.hashes.ui.AddressBadge
import com.corejbh.hashes.ui.theme.Crt

/**
 * `PAYOUT_TARGET>` prompt with the paste and QR buttons.
 *
 * The validation badge updates as the text changes, using the same validator the
 * miner uses before it connects: what a user sees as `[VALID_MAINNET]` is exactly
 * what the session will accept.
 */
@Composable
fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val validation = BitcoinAddressValidator.validate(value)
    val badge = AddressBadge.of(validation)
    val valid = AddressBadge.isValid(validation)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (valid) Crt.PhosphorDim else Crt.PhosphorFaint)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.payout_prompt),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Crt.Phosphor,
                ),
            )
            Box(Modifier.padding(start = 6.dp)) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.payout_placeholder),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Crt.PhosphorFaint,
                        ),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Crt.Phosphor,
                    ),
                    cursorBrush = SolidColor(Crt.Phosphor),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalButton(
                text = stringResource(R.string.button_paste),
                onClick = {
                    val text = clipboard.getText()?.text
                    if (!text.isNullOrBlank()) {
                        onValueChange(BitcoinUri.extractAddress(text) ?: text.trim())
                    }
                },
            )
            TerminalButton(text = stringResource(R.string.button_scan_qr), onClick = onScanQr)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalBadge(text = badge.badge, inverted = valid, dim = !valid)
            Text(
                text = badge.detail,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Crt.PhosphorDim,
                ),
            )
        }
    }
}
