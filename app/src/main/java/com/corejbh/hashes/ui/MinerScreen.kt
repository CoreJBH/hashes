package com.corejbh.hashes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.corejbh.hashes.MinerSession
import com.corejbh.hashes.core.EngineCounters
import com.corejbh.hashes.core.Hex
import com.corejbh.hashes.core.MinerMode
import com.corejbh.hashes.engine.NativeMiner
import com.corejbh.hashes.thermal.ThermalAction
import com.corejbh.hashes.thermal.ThermalDecision
import com.corejbh.hashes.thermal.ThermalSource
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Phase 1 screen.
 *
 * It is a workbench, not the product: a fixed job, the numbers the engine
 * reports through the bridge, and the controls needed to see COOL and TURBO do
 * their thing on a real device. The designed terminal UI comes with the network
 * phase; what is here is deliberately plain so a wrong number is easy to spot.
 */
@Composable
fun MinerScreen(session: MinerSession) {
    var counters by remember { mutableStateOf(EngineCounters.IDLE) }
    var found by remember { mutableIntStateOf(0) }
    var liveHash by remember { mutableStateOf<String?>(null) }
    val thermal by session.thermalDecision.collectAsStateWithLifecycle()
    val digest = remember { ByteArray(NativeMiner.DIGEST_SIZE) }

    LaunchedEffect(session.running) {
        if (!session.running) {
            return@LaunchedEffect
        }
        while (session.running) {
            counters = session.counters()
            while (session.takeFoundHash(digest) >= 0L) {
                found += 1
            }
            session.currentHash()?.let { liveHash = Hex.toDisplayHex(it) }
            delay(FRAME_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "HASHES",
            color = PhosphorGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp,
        )
        Text(
            text = "SHA-256 BLOCK HEADER ENGINE / PHASE 1 CORE",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )

        Spacer(Modifier.height(24.dp))

        if (!session.available) {
            Section {
                TerminalLine("ENGINE", "UNAVAILABLE")
                TerminalLine("DETAIL", session.unavailableReason() ?: "native library did not load")
                TerminalLine("NOTE", "This build carries the engine for arm64 phones only.")
            }
            return@Column
        }

        Section {
            TerminalLine("ENGINE", "NATIVE / " + engineText(counters.hardwareSha256))
            TerminalLine("STATE", counters.state.name)
            TerminalLine(
                "MODE",
                if (session.mode == MinerMode.TURBO) {
                    "TURBO / ${counters.threads} THREADS"
                } else {
                    "COOL / 1 THREAD"
                },
            )
            TerminalLine("JOB", "BLOCK #0 HEADER / SELF-TEST")
            TerminalLine("VERSION", session.version)
        }

        Spacer(Modifier.height(14.dp))

        Section {
            TerminalLine("HASHRATE", formatRate(counters.hashRate))
            TerminalLine("HASHES", formatCount(counters.hashes))
            TerminalLine("NONCE", formatNonce(counters.nonce))
            TerminalLine("FOUND", formatCount(found.toLong()))
            TerminalLine("BEST ZEROS", counters.bestLeadingZeroNibbles.toString() + " NIBBLES")
            TerminalLine("THERMAL", thermalText(thermal))
            TerminalLine("HASH", liveHash ?: "--")
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TerminalButton(
                label = if (session.running) "STOP" else "START",
                active = session.running,
            ) {
                if (session.running) session.stop() else session.start()
            }
            TerminalButton(
                label = "COOL",
                active = session.mode == MinerMode.COOL,
            ) {
                session.selectMode(MinerMode.COOL)
            }
            TerminalButton(
                label = "TURBO",
                active = session.mode == MinerMode.TURBO,
            ) {
                session.selectMode(MinerMode.TURBO)
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Mines a fixed mainnet header while this screen is open. " +
                "No pool, no network, no address: found hashes stay on the device in this phase.",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

}

private const val FRAME_MS = 33L

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PhosphorDim)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun TerminalLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            color = PhosphorGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TerminalButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (active) PhosphorGreen else PhosphorDim)
            .background(if (active) PhosphorGreen else TerminalBlack)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (active) TerminalBlack else PhosphorGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

private fun engineText(hardware: Boolean): String =
    if (hardware) "[ARMv8 SHA2-256 BOOST]" else "[C PORTABLE]"

private fun formatRate(rate: Long): String = when {
    rate >= 1_000_000L -> String.format(Locale.US, "%.2f MH/s", rate / 1_000_000.0)
    rate >= 1_000L -> String.format(Locale.US, "%.2f kH/s", rate / 1_000.0)
    else -> String.format(Locale.US, "%d H/s", rate)
}

private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

private fun formatNonce(nonce: Long): String = String.format(Locale.US, "0x%08x", nonce)

private fun thermalText(decision: ThermalDecision): String {
    val state = when (decision.action) {
        ThermalAction.NONE -> "NOMINAL"
        ThermalAction.HALVE_THREADS -> "MODERATE / THREADS HALVED"
        ThermalAction.COOL -> "SEVERE / COOL MODE"
    }
    val reading = when (decision.source) {
        ThermalSource.BATTERY_TEMPERATURE ->
            String.format(Locale.US, " %.1f C", decision.reading / 10.0)
        ThermalSource.THERMAL_STATUS -> ""
    }
    return state + reading
}
