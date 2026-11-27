package com.corejbh.hashes.ui

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The per-worker view of the engine: a bar for each worker slot and the rate that
 * slot actually produced between two counter reads.
 *
 * The counters come from the core (one cumulative total per worker); the rate is
 * a difference of two reads over the time between them, so a bar is what that
 * thread hashed, never an average of the others or a number the UI made up. A
 * slot with no hashes in the interval shows an empty bar, and a worker set that
 * changed (COOL to TURBO, a thermal cap) restarts the measurement instead of
 * mixing two sets of slots.
 */
object WorkerMeter {

    /** Cells in a bar at the terminal's width. */
    const val CELLS = 10

    const val FILLED = '\u2588'
    const val EMPTY = '\u2591'

    /**
     * Hashes per second for each worker slot between two cumulative reads.
     * A negative delta (a counter that was reset) counts as zero.
     */
    fun rates(previous: List<Long>, current: List<Long>, seconds: Double): List<Double> {
        if (seconds <= 0.0) return List(current.size) { 0.0 }
        if (previous.size != current.size) return List(current.size) { 0.0 }
        return current.indices.map { index ->
            val delta = max(0L, current[index] - previous[index])
            delta / seconds
        }
    }

    /** How full one worker's bar is, relative to the busiest worker of the set. */
    fun bar(rate: Double, peak: Double, cells: Int = CELLS): String {
        if (cells <= 0) return ""
        if (peak <= 0.0 || rate <= 0.0) return EMPTY.toString().repeat(cells)
        val filled = (rate / peak * cells).roundToInt().coerceIn(0, cells)
        return FILLED.toString().repeat(filled) + EMPTY.toString().repeat(cells - filled)
    }

    /** A worker's rate in as few characters as the line can afford: `52.3K`. */
    fun label(rate: Double): String = when {
        rate.isNaN() || rate.isInfinite() || rate <= 0.0 -> "0"
        rate >= 1e9 -> one(rate / 1e9) + "G"
        rate >= 1e6 -> one(rate / 1e6) + "M"
        rate >= 1e3 -> one(rate / 1e3) + "K"
        else -> rate.roundToInt().toString()
    }

    private fun one(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded >= 100) rounded.roundToInt().toString() else rounded.toString()
    }
}
