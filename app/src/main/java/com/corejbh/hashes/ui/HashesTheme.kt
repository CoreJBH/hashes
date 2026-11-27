package com.corejbh.hashes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The palette the app is built around: deep black, phosphor green. */
val TerminalBlack = Color(0xFF030604)
val PhosphorGreen = Color(0xFF00FF66)
val PhosphorDim = Color(0xFF0B8A3E)

private val HashesColorScheme = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = TerminalBlack,
    background = TerminalBlack,
    onBackground = PhosphorGreen,
    surface = TerminalBlack,
    onSurface = PhosphorGreen,
    surfaceVariant = Color(0xFF07120B),
    onSurfaceVariant = PhosphorDim,
)

@Composable
fun HashesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HashesColorScheme, content = content)
}
