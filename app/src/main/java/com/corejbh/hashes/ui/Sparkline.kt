package com.corejbh.hashes.ui

import kotlin.math.roundToInt

/**
 * The history graphs of the monitor: one value per sample, drawn with the eight
 * block glyphs a terminal has. The scale is the window's own minimum and maximum,
 * the way `htop` draws a graph, so a flat line at 360 KH/s is flat rather than
 * pinned to the floor.
 */
object Sparkline {

    /** The block glyphs, lowest first. */
    const val LEVELS = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588"

    /** The glyph a constant window draws: the middle of the scale. */
    const val FLAT = '\u2584'

    /**
     * [values] as block glyphs, newest last, at most [width] samples and left
     * padded with the lowest glyph so the axis does not move as history fills.
     */
    fun render(values: List<Double>, width: Int = DEFAULT_WIDTH): String {
        if (width <= 0) return ""
        val window = values.takeLast(width)
        val text = StringBuilder(width)
        repeat(width - window.size) { text.append(LEVELS.first()) }
        if (window.isEmpty()) return text.toString()

        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        for (value in window) {
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
        val span = maximum - minimum
        for (value in window) {
            if (span <= 0.0) {
                text.append(FLAT)
            } else {
                val level = ((value - minimum) / span * (LEVELS.length - 1)).roundToInt()
                text.append(LEVELS[level.coerceIn(0, LEVELS.length - 1)])
            }
        }
        return text.toString()
    }

    const val DEFAULT_WIDTH = 24
}
