package com.corejbh.hashes.thermal

/** What the engine should do about the reading it just took. */
enum class ThermalAction {
    /** Nothing: let the engine keep the mode and thread count it has. */
    NONE,

    /** TURBO halves its worker threads. */
    HALVE_THREADS,

    /** The engine goes back to COOL: one thread and the 30 fps duty cycle. */
    COOL,
}

/** Where a reading came from, so the screen can say which path it is on. */
enum class ThermalSource {
    /** API 29+ PowerManager thermal status. */
    THERMAL_STATUS,

    /** API 26-28 battery temperature in tenths of a degree Celsius. */
    BATTERY_TEMPERATURE,
}

data class ThermalDecision(
    val action: ThermalAction,
    val source: ThermalSource,
    val reading: Int,
) {
    companion object {
        fun none(): ThermalDecision =
            ThermalDecision(ThermalAction.NONE, ThermalSource.THERMAL_STATUS, ThermalPolicy.STATUS_NONE)
    }
}

/**
 * The two-stage heat protection, as pure arithmetic so it is unit-tested on the
 * JVM rather than only on a hot phone.
 *
 * MODERATE (or 40.0 C) halves the threads; SEVERE (or 43.0 C) returns the engine
 * to COOL. Anything worse than SEVERE is treated as SEVERE - the platform's
 * CRITICAL, EMERGENCY and SHUTDOWN statuses only exist because SEVERE was not
 * enough, so they cannot mean less.
 */
object ThermalPolicy {

    // PowerManager's thermal status values. Repeated here because the platform
    // constants read as 0 in a JVM unit test, which would make this table
    // untestable; the values themselves are part of the public API and stable.
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3

    /** Battery temperature at which TURBO halves its threads, in tenths of a degree. */
    const val BATTERY_TEMPERATURE_HALVE_DECI_CELSIUS = 400

    /** Battery temperature at which the engine returns to COOL, in tenths of a degree. */
    const val BATTERY_TEMPERATURE_COOL_DECI_CELSIUS = 430

    fun forThermalStatus(status: Int): ThermalAction = when {
        status >= STATUS_SEVERE -> ThermalAction.COOL
        status == STATUS_MODERATE -> ThermalAction.HALVE_THREADS
        else -> ThermalAction.NONE
    }

    fun forBatteryTemperature(deciCelsius: Int): ThermalAction = when {
        deciCelsius >= BATTERY_TEMPERATURE_COOL_DECI_CELSIUS -> ThermalAction.COOL
        deciCelsius >= BATTERY_TEMPERATURE_HALVE_DECI_CELSIUS -> ThermalAction.HALVE_THREADS
        else -> ThermalAction.NONE
    }
}
