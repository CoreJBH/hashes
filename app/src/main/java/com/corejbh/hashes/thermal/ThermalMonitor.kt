package com.corejbh.hashes.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Reads the device's heat, the best way the running Android version offers.
 *
 * API 29 and later: the platform's own thermal status, pushed to us when it
 * changes. API 26-28: no thermal status API exists, so the battery temperature
 * is polled from the sticky battery broadcast and the same two thresholds are
 * applied by hand. Both paths end in [ThermalPolicy], so the decision is the
 * same shape whichever reading it came from.
 *
 * Callbacks arrive on the main thread, and the monitor is inert until [start].
 */
class ThermalMonitor(
    context: Context,
    private val onDecision: (ThermalDecision) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var statusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var polling = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) {
                return
            }
            readBatteryTemperature()?.let(onDecision)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startStatusListener()
        } else {
            polling = true
            handler.post(pollTask)
        }
    }

    fun stop() {
        polling = false
        handler.removeCallbacks(pollTask)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            statusListener?.let { listener ->
                powerManager()?.removeThermalStatusListener(listener)
            }
        }
        statusListener = null
    }

    /** The reading now, without waiting for the next change. */
    fun current(): ThermalDecision {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = powerManager()
            if (power != null) {
                return statusDecision(power.currentThermalStatus)
            }
        }
        return readBatteryTemperature()
            ?: ThermalDecision.none()
    }

    private fun startStatusListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || statusListener != null) {
            return
        }
        val power = powerManager() ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            onDecision(statusDecision(status))
        }
        statusListener = listener
        power.addThermalStatusListener(appContext.mainExecutor, listener)
        onDecision(statusDecision(power.currentThermalStatus))
    }

    private fun statusDecision(status: Int): ThermalDecision = ThermalDecision(
        action = ThermalPolicy.forThermalStatus(
            when (status) {
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalPolicy.STATUS_MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalPolicy.STATUS_SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN,
                -> ThermalPolicy.STATUS_SEVERE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalPolicy.STATUS_LIGHT
                else -> ThermalPolicy.STATUS_NONE
            },
        ),
        source = ThermalSource.THERMAL_STATUS,
        reading = status,
    )

    private fun readBatteryTemperature(): ThermalDecision? {
        val sticky: Intent? = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val deciCelsius = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        if (deciCelsius == Int.MIN_VALUE) {
            return null
        }
        return ThermalDecision(
            action = ThermalPolicy.forBatteryTemperature(deciCelsius),
            source = ThermalSource.BATTERY_TEMPERATURE,
            reading = deciCelsius,
        )
    }

    private fun powerManager(): PowerManager? =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L
    }
}
