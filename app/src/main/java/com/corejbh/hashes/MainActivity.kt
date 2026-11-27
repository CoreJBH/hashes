package com.corejbh.hashes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
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

    val context = LocalContext.current

    /* Android 13+ wants the notification permission before a foreground service
     * can put its status in the shade. A refusal does not stop the session: the
     * service runs either way and shows up in the system's own task manager. */
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // The screen stays awake while a session runs; it does not need to when the
    // service has the session (the terminal is not the thing keeping it alive).
    val view = LocalView.current
    DisposableEffect(state.mining) {
        view.keepScreenOn = state.mining
        onDispose { view.keepScreenOn = false }
    }

    /* ON_START and ON_STOP drive the frame polling only. The session itself is
     * the foreground service's; leaving the screen must not stop it. */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenVisible()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onScreenVisible()
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
                        } else {
                            requestNotifications(notificationPermission::launch, context)
                            if (!viewModel.startMining()) {
                                showSettings = true
                            }
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

/** Asks once, on Android 13+, and never blocks the session on the answer. */
private fun requestNotifications(
    launch: (String) -> Unit,
    context: android.content.Context,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) launch(Manifest.permission.POST_NOTIFICATIONS)
}
