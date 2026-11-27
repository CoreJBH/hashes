package com.corejbh.hashes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.corejbh.hashes.ui.MinerScreen
import com.corejbh.hashes.ui.MinerViewModel
import com.corejbh.hashes.ui.SplashScreen
import com.corejbh.hashes.ui.components.SettingsSheet
import com.corejbh.hashes.ui.scan.QrScannerScreen
import com.corejbh.hashes.ui.theme.HashesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HashesTheme {
                HashesApp()
            }
        }
    }
}

@Composable
fun HashesApp(viewModel: MinerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scannedValue by remember { mutableStateOf<String?>(null) }

    // Mining only runs while this screen is visible, so the screen must not sleep
    // under it. The flag is dropped the moment the session stops.
    val view = LocalView.current
    DisposableEffect(state.mining) {
        view.keepScreenOn = state.mining
        onDispose { view.keepScreenOn = false }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onScreenHidden()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Crossfade(
        targetState = showSplash,
        animationSpec = tween(durationMillis = 420),
        label = "boot",
    ) { splash ->
        if (splash) {
            SplashScreen(onFinished = { showSplash = false })
        } else {
            Box(Modifier.fillMaxSize()) {
                MinerScreen(
                    state = state,
                    onToggleMining = {
                        if (state.mining) {
                            viewModel.stopMining()
                        } else if (!viewModel.startMining()) {
                            showSettings = true
                        }
                    },
                    onTurboChange = viewModel::setTurbo,
                    onOpenSettings = { showSettings = true },
                    onResetGuard = viewModel::clearThermalGuard,
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            scannedValue = scannedValue,
            onScannedHandled = { scannedValue = null },
            onScanRequest = { showScanner = true },
            onApply = { settings ->
                viewModel.applySettings(settings)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showScanner) {
        QrScannerScreen(
            onResult = { text ->
                scannedValue = text
                showScanner = false
            },
            onClose = { showScanner = false },
        )
    }
}
