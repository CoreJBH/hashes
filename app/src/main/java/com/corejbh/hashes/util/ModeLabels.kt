package com.corejbh.hashes.util

import java.util.Locale

/**
 * The mode words the terminal and the notification print.
 *
 * The default mode is never named: it is the absence of TURBO, so the title
 * reads `HASHES`, the header badge carries the temperature alone and the worker
 * line carries the thread count alone. Only TURBO, and the guard that takes it
 * away, earn a word — a screen where every line repeats `COOL` spends the
 * reader's attention on a state that is not news.
 */
object ModeLabels {

    const val TURBO = "TURBO"
    const val GUARD = "GUARD"

    /** `HASHES` by default, `HASHES · TURBO` while TURBO runs. */
    fun title(turbo: Boolean): String = if (turbo) "HASHES \u00b7 TURBO" else "HASHES"

    /**
     * The header badge: `38°C` by default, `TURBO · 38°C` while TURBO runs,
     * `TURBO` when there is no temperature, and empty when there is neither —
     * the caller then draws no badge at all.
     */
    fun thermalBadge(turbo: Boolean, temperatureCelsius: Double?): String {
        val degrees = temperatureCelsius?.let {
            String.format(Locale.US, "%.0f", it) + "\u00b0C"
        }
        return when {
            turbo && degrees != null -> "$TURBO \u00b7 $degrees"
            turbo -> TURBO
            else -> degrees ?: ""
        }
    }

    /**
     * The WORKERS trailer: `1T` by default, `TURBO 7T` while TURBO runs, the
     * capped form `TURBO 7T / CAP 4T`, and `GUARD` once the heat took TURBO
     * away. `--` stands in for a worker count the engine has not reported.
     */
    fun workerTrailer(
        turbo: Boolean,
        guard: Boolean,
        threads: Int,
        capped: Boolean,
        threadCap: Int,
    ): String {
        if (guard) return GUARD
        val count = if (threads > 0) "${threads}T" else "--"
        return when {
            !turbo -> count
            capped -> "$TURBO $count / CAP ${threadCap}T"
            else -> "$TURBO $count"
        }
    }
}
