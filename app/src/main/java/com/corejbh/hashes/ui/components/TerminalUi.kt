package com.corejbh.hashes.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.hashes.ui.theme.Crt

/** Every terminal line is monospace; nothing else is used anywhere in the app. */
val TerminalTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    letterSpacing = 0.3.sp,
    color = Crt.Phosphor,
)

@Composable
fun TerminalDivider(modifier: Modifier = Modifier, color: Color = Crt.PhosphorFaint) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = TerminalTextStyle.copy(
                color = Crt.PhosphorDim,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
            )
        }
    }
}

@Composable
fun TerminalRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Crt.Phosphor,
    labelWidth: Int = 19,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.padEnd(labelWidth),
            style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = TerminalTextStyle.copy(color = valueColor, fontSize = 11.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A badge: `[VALID_MAINNET]`. Emphasis swaps the phosphor and the field. */
@Composable
fun TerminalBadge(
    text: String,
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
    dim: Boolean = false,
) {
    val background = when {
        inverted -> Crt.InverseBackground
        else -> Color.Transparent
    }
    val foreground = when {
        inverted -> Crt.InverseText
        dim -> Crt.PhosphorDim
        else -> Crt.Phosphor
    }
    Box(
        modifier = modifier
            .background(background)
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = TerminalTextStyle.copy(
                color = foreground,
                fontSize = 10.sp,
                fontWeight = if (inverted) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

/** A square, one-pixel-border button, the only button shape in the app. */
@Composable
fun TerminalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inverted: Boolean = false,
) {
    val borderColor = when {
        !enabled -> Crt.PhosphorFaint
        inverted -> Crt.Phosphor
        else -> Crt.PhosphorDim
    }
    val textColor = when {
        !enabled -> Crt.PhosphorFaint
        inverted -> Crt.Phosphor
        else -> Crt.PhosphorDim
    }
    Box(
        modifier = modifier
            .border(width = 1.dp, color = borderColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TerminalTextStyle.copy(
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
        )
    }
}

/** The block cursor a terminal leaves blinking. */
@Composable
fun BlinkingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Box(
        modifier
            .padding(start = 2.dp)
            .size(width = 7.dp, height = 13.dp)
            .background(Crt.Phosphor.copy(alpha = alpha))
    )
}

/** A vertical scroll bar drawn as terminal blocks, so the list reads as a log. */
@Composable
fun VerticalRule(modifier: Modifier = Modifier, color: Color = Crt.PhosphorFaint) {
    Box(modifier.width(1.dp).background(color))
}
