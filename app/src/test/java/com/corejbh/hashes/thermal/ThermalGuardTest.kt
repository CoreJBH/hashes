package com.corejbh.hashes.thermal

import com.corejbh.hashes.engine.EnginePowerControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardTest {

    private class FakeControl : EnginePowerControl {
        var turbo = false
        val caps = mutableListOf<Int>()
        var turboCalls = 0

        override fun setTurboMode(enabled: Boolean) {
            turbo = enabled
            turboCalls += 1
        }

        override fun setThreadCap(threads: Int) {
            caps += threads
        }
    }

    private fun reading(action: ThermalAction): ThermalDecision =
        ThermalDecision(action, ThermalSource.THERMAL_STATUS, ThermalPolicy.STATUS_MODERATE)

    private fun guard(control: FakeControl, maxThreads: Int = 8): ThermalGuard =
        ThermalGuard(control, maxThreads).also { it.onUserTurbo(true) }

    @Test
    fun moderateHalvesTurboWorkers() {
        val control = FakeControl()
        val guard = guard(control)

        guard.onReading(reading(ThermalAction.HALVE_THREADS))

        assertEquals(listOf(8, 4), control.caps)
        assertEquals(4, guard.state.value.threadCap)
        assertTrue(control.turbo)
    }

    @Test
    fun aCleanReadingRestoresTheFullWorkerSet() {
        val control = FakeControl()
        val guard = guard(control)

        guard.onReading(reading(ThermalAction.HALVE_THREADS))
        guard.onReading(reading(ThermalAction.NONE))

        assertEquals(8, control.caps.last())
        assertEquals(8, guard.state.value.threadCap)
    }

    @Test
    fun oneSevereReadingCapsToOneWorkerWithoutEndingTurbo() {
        val control = FakeControl()
        val guard = guard(control)

        guard.onReading(reading(ThermalAction.COOL))

        assertEquals(1, control.caps.last())
        assertTrue(control.turbo)
        assertFalse(guard.state.value.tripped)
        assertEquals(1, guard.state.value.strikes)
    }

    @Test
    fun twoConsecutiveSevereReadingsTakeTurboAway() {
        val control = FakeControl()
        val guard = guard(control)

        guard.onReading(reading(ThermalAction.COOL))
        guard.onReading(reading(ThermalAction.COOL))

        assertFalse(control.turbo)
        assertTrue(guard.state.value.tripped)
        assertEquals(ThermalGuard.SEVERE_STRIKES_TO_TRIP, guard.state.value.strikes)
    }

    @Test
    fun aCleanReadingBetweenSevereOnesResetsTheCount() {
        val control = FakeControl()
        val guard = guard(control)

        guard.onReading(reading(ThermalAction.COOL))
        guard.onReading(reading(ThermalAction.NONE))
        guard.onReading(reading(ThermalAction.COOL))

        assertTrue(control.turbo)
        assertFalse(guard.state.value.tripped)
        assertEquals(1, guard.state.value.strikes)
    }

    @Test
    fun turboIsRefusedWhileTheGuardIsTripped() {
        val control = FakeControl()
        val guard = guard(control)
        guard.onReading(reading(ThermalAction.COOL))
        guard.onReading(reading(ThermalAction.COOL))

        assertFalse(guard.onUserTurbo(true))
        assertFalse(control.turbo)
        assertTrue(guard.state.value.tripped)
    }

    @Test
    fun resetClearsTheTripAndAllowsTurboAgain() {
        val control = FakeControl()
        val guard = guard(control)
        guard.onReading(reading(ThermalAction.COOL))
        guard.onReading(reading(ThermalAction.COOL))

        guard.reset()

        assertFalse(guard.state.value.tripped)
        assertEquals(0, guard.state.value.strikes)
        assertEquals(8, control.caps.last())
        assertTrue(guard.onUserTurbo(true))
        assertTrue(control.turbo)
    }

    @Test
    fun aUserTurboRequestStartsFromTheFullWorkerSet() {
        val control = FakeControl()
        val guard = guard(control)
        guard.onReading(reading(ThermalAction.HALVE_THREADS))
        guard.onUserTurbo(false)

        guard.onUserTurbo(true)

        assertTrue(control.turbo)
        assertEquals(8, control.caps.last())
    }

    @Test
    fun aSingleWorkerDeviceNeverAsksForZeroThreads() {
        val control = FakeControl()
        val guard = ThermalGuard(control, maxThreads = 1).also { it.onUserTurbo(true) }

        guard.onReading(reading(ThermalAction.HALVE_THREADS))

        assertEquals(1, control.caps.last())
        assertEquals(1, guard.state.value.threadCap)
    }
}
