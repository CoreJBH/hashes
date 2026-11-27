package com.corejbh.hashes.thermal

import com.corejbh.hashes.core.MinerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the thermal controller is allowed to do to the engine. Kept as an
 * interface so the controller can be tested with a fake and so it does not
 * depend on the JNI binding.
 */
interface TurboEngineControl {
    /** Switch the engine between TURBO and COOL. */
    fun setTurboMode(turbo: Boolean)

    /**
     * Cap how many workers TURBO may use. The finer of the two thermal steps;
     * the bridge contract has no method for it, so an engine that does not
     * support the cap keeps its full worker set.
     */
    fun setThreadCap(threads: Int)
}

/**
 * Applies [ThermalDecision]s to the engine.
 *
 * MODERATE halves TURBO's threads, SEVERE returns the engine to COOL, and the
 * engine stays COOL while the reading is severe even if the user asks for TURBO:
 * a hot phone must not be one tap away from full speed.
 */
class ThermalController(
    private val control: TurboEngineControl,
    val maxThreads: Int,
) {
    private val _decision = MutableStateFlow(ThermalDecision.none())
    val decision: StateFlow<ThermalDecision> = _decision.asStateFlow()

    private var turbo = false
    private var throttled = false

    val mode: MinerMode get() = if (turbo) MinerMode.TURBO else MinerMode.COOL

    /** The user picked a mode; a severe reading overrides TURBO. */
    fun onUserMode(mode: MinerMode) {
        if (mode == MinerMode.TURBO && _decision.value.action == ThermalAction.COOL) {
            return
        }
        turbo = mode == MinerMode.TURBO
        throttled = false
        control.setThreadCap(maxThreads)
        control.setTurboMode(turbo)
    }

    fun onDecision(decision: ThermalDecision) {
        _decision.value = decision
        when (decision.action) {
            ThermalAction.NONE -> {
                if (throttled) {
                    throttled = false
                    control.setThreadCap(maxThreads)
                }
            }

            ThermalAction.HALVE_THREADS -> {
                throttled = true
                if (turbo) {
                    control.setThreadCap(maxOf(1, maxThreads / 2))
                }
            }

            ThermalAction.COOL -> {
                throttled = true
                turbo = false
                control.setThreadCap(maxThreads)
                control.setTurboMode(false)
            }
        }
    }
}
