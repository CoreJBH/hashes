package com.corejbh.hashes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The whole palette. There is no second colour: the app is a phosphor terminal,
 * and emphasis is the green and the background trading places.
 */
object Crt {
    val Background = Color(0xFF030604)
    val Phosphor = Color(0xFF00FF66)
    val PhosphorBright = Color(0xFF66FFA6)
    val PhosphorDim = Color(0xFF00A344)
    val PhosphorFaint = Color(0xFF0B3A1F)
    val Scanline = Color(0x0D00FF66)
    val Glow = Color(0x3300FF66)
    val InverseText = Background
    val InverseBackground = Phosphor
}

private val mono = FontFamily.Monospace

val TerminalTypography = Typography(
    displayLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 6.sp),
    titleLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.5.sp),
    titleMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 14.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 12.sp, letterSpacing = 0.4.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 0.6.sp),
    labelLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 9.sp, letterSpacing = 1.sp),
)

private val scheme = darkColorScheme(
    primary = Crt.Phosphor,
    onPrimary = Crt.Background,
    secondary = Crt.PhosphorDim,
    onSecondary = Crt.Background,
    background = Crt.Background,
    onBackground = Crt.Phosphor,
    surface = Crt.Background,
    onSurface = Crt.Phosphor,
    surfaceVariant = Crt.Background,
    onSurfaceVariant = Crt.PhosphorDim,
    outline = Crt.PhosphorDim,
    error = Crt.Phosphor,
    onError = Crt.Background,
)

@Composable
fun HashesTheme(content: @Composable () -> Unit) {
    // Dark is the only look; the flag is read so a device that reports light
    // mode still gets the terminal, not a Material default.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = TerminalTypography, content = content)
}

/** Faint horizontal lines over the content, the way a CRT draws a field. */
fun Modifier.crtScanlines(alpha: Float = 0.06f): Modifier = drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = Crt.Phosphor.copy(alpha = alpha),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += step
    }
}

val glowShadow = Shadow(color = Crt.Glow, blurRadius = 12f)
