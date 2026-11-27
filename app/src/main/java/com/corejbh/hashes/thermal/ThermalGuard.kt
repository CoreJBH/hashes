package com.corejbh.hashes.thermal

import com.corejbh.hashes.engine.EnginePowerControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the guard has done to the engine, as the terminal shows it.
 *
 * @param decision the reading that produced this state
 * @param strikes consecutive readings the policy called COOL (SEVERE or worse)
 * @param threadsCapped TURBO is running on a reduced worker set
 * @param tripped TURBO was taken away by heat and stays off until a reset
 */
data class ThermalGuardState(
    val decision: ThermalDecision = ThermalDecision.none(),
    val strikes: Int = 0,
    val threadsCapped: Boolean = false,
    val tripped: Boolean = false,
    val maxThreads: Int = 1,
) {
    /** The worker cap currently in force, for the status line. */
    val threadCap: Int
        get() = when {
            strikes > 0 -> 1
            threadsCapped -> maxOf(1, maxThreads / 2)
            else -> maxThreads
        }
}

/**
 * Applies [ThermalDecision]s to the engine in two steps, and fires the visible
 * last resort when the device stays hot.
 *
 * - MODERATE (or 40.0 C): TURBO keeps running, capped to half its workers through
 *   `nativeSetThreadCap`. The phone loses speed, not the session.
 * - SEVERE (or 43.0 C), first reading: TURBO is capped down to a single worker.
 * - SEVERE, second consecutive reading: TURBO is switched off, TURBO stays
 *   refused, and [ThermalGuardState.tripped] is raised so the terminal can print
 *   `THERMAL GUARD`. A single spurious reading cannot end a session; two in a row
 *   are the device saying it is hot, not the sensor.
 *
 * A clean reading puts the cap back. [reset] is the user's own way out of the
 * tripped state; TURBO is refused until then, so a hot phone is never one tap
 * away from full speed.
 */
class ThermalGuard(
    private val control: EnginePowerControl,
    maxThreads: Int,
) {
    private val limit = maxOf(1, maxThreads)

    private val _state = MutableStateFlow(
        ThermalGuardState(maxThreads = limit),
    )
    val state: StateFlow<ThermalGuardState> = _state.asStateFlow()

    private var turbo = false
    private var tripped = false
    private var strikes = 0
    private var capped = false

    /** The reading the sensor just reported. */
    fun onReading(decision: ThermalDecision) {
        when (decision.action) {
            ThermalAction.NONE -> {
                strikes = 0
                if (capped && !tripped) {
                    capped = false
                    control.setThreadCap(limit)
                }
                publish(decision)
            }

            ThermalAction.HALVE_THREADS -> {
                strikes = 0
                capped = true
                if (turbo && !tripped) {
                    control.setThreadCap(halfOfLimit())
                }
                publish(decision)
            }

            ThermalAction.COOL -> {
                strikes += 1
                capped = true
                if (strikes >= SEVERE_STRIKES_TO_TRIP) {
                    trip(decision)
                } else if (turbo && !tripped) {
                    /* First severe reading: one worker, still TURBO. If the next
                     * reading is clean this is undone by the NONE branch. */
                    control.setThreadCap(1)
                }
                publish(decision)
            }
        }
    }

    /**
     * The user tapped TURBO. Refused while the guard is tripped: the phone has to
     * cool down and the user has to press reset first. Returns true when the
     * request was applied.
     */
    fun onUserTurbo(enabled: Boolean): Boolean {
        if (enabled && tripped) {
            return false
        }
        turbo = enabled
        if (enabled) {
            /* A deliberate request starts from the full worker set; the next
             * reading re-applies whatever cap it calls for. */
            capped = false
            strikes = 0
            control.setThreadCap(limit)
        }
        control.setTurboMode(enabled)
        publish(_state.value.decision)
        return true
    }

    /** The RESET THERMAL GUARD button: clears the strikes and re-allows TURBO. */
    fun reset() {
        tripped = false
        strikes = 0
        capped = false
        control.setThreadCap(limit)
        publish(ThermalDecision.none())
    }

    /** True when TURBO is currently the engine's mode. */
    fun turboEnabled(): Boolean = turbo

    private fun trip(decision: ThermalDecision) {
        turbo = false
        tripped = true
        capped = true
        control.setThreadCap(1)
        control.setTurboMode(false)
        publish(decision)
    }

    private fun halfOfLimit(): Int = maxOf(1, limit / 2)

    private fun publish(decision: ThermalDecision) {
        _state.value = ThermalGuardState(
            decision = decision,
            strikes = strikes,
            threadsCapped = capped,
            tripped = tripped,
            maxThreads = limit,
        )
    }

    companion object {
        /** Consecutive SEVERE readings before TURBO is taken away for good. */
        const val SEVERE_STRIKES_TO_TRIP = 2
    }
}
