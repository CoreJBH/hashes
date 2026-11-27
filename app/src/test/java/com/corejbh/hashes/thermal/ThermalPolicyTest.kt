package com.corejbh.hashes.thermal

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun statusBelowModerateLeavesTheEngineAlone() {
        assertEquals(ThermalAction.NONE, ThermalPolicy.forThermalStatus(ThermalPolicy.STATUS_NONE))
        assertEquals(ThermalAction.NONE, ThermalPolicy.forThermalStatus(ThermalPolicy.STATUS_LIGHT))
    }

    @Test
    fun moderateHalvesTheThreads() {
        assertEquals(
            ThermalAction.HALVE_THREADS,
            ThermalPolicy.forThermalStatus(ThermalPolicy.STATUS_MODERATE),
        )
    }

    @Test
    fun severeAndWorseReturnToCool() {
        // The platform's SEVERE is the documented trip point; CRITICAL, EMERGENCY
        // and SHUTDOWN are worse, so they cannot mean less.
        for (status in ThermalPolicy.STATUS_SEVERE..6) {
            assertEquals(
                "status $status",
                ThermalAction.COOL,
                ThermalPolicy.forThermalStatus(status),
            )
        }
    }

    @Test
    fun batteryTemperatureUsesTheSameTwoSteps() {
        assertEquals(ThermalAction.NONE, ThermalPolicy.forBatteryTemperature(395))
        assertEquals(ThermalAction.HALVE_THREADS, ThermalPolicy.forBatteryTemperature(400))
        assertEquals(ThermalAction.HALVE_THREADS, ThermalPolicy.forBatteryTemperature(429))
        assertEquals(ThermalAction.COOL, ThermalPolicy.forBatteryTemperature(430))
        assertEquals(ThermalAction.COOL, ThermalPolicy.forBatteryTemperature(500))
    }
}
