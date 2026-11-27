package com.corejbh.hashes.engine

/**
 * The two things a thermal step is allowed to do to a running engine.
 *
 * Kept as its own interface so the thermal guard can be tested against a fake and
 * so the engine has no dependency on the sensor side: the guard decides, the
 * engine obeys.
 */
interface EnginePowerControl {

    /** Switch the engine between TURBO and COOL. */
    fun setTurboMode(enabled: Boolean)

    /**
     * Cap how many workers TURBO may use: the MODERATE step between full speed and
     * COOL. The native core clamps it to the device's worker limit; an engine with
     * a single worker (the Kotlin fallback) has nothing to cap and keeps this
     * no-op.
     */
    fun setThreadCap(threads: Int) {}
}
