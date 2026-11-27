package com.corejbh.hashes.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.corejbh.hashes.R
import com.corejbh.hashes.ui.components.TerminalButton
import com.corejbh.hashes.ui.components.TerminalTextStyle
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.ui.theme.crtScanlines
import java.util.concurrent.Executors

/**
 * The QR scanner: a CameraX preview under a green CRT frame, with the same
 * monochrome treatment as the terminal and a scan line that keeps sweeping the
 * target box.
 *
 * Permission is asked once through the activity result API; a refusal leaves a
 * readable terminal message and the typed field still works. The camera is bound
 * to the current lifecycle and unbound when the screen leaves composition.
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed -> granted = allowed }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val sweepTransition = rememberInfiniteTransition(label = "qr-sweep")
    val sweep by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "qrSweep",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Crt.Background)
            .crtScanlines(alpha = 0.07f)
    ) {
        if (granted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            DisposableEffect(granted, lifecycleOwner, previewView) {
                val view = previewView
                // Binding waits for the PreviewView to exist: the camera provider
                // callback can otherwise run before the AndroidView factory has
                // created one, and the session would never start.
                if (view == null) {
                    onDispose { }
                } else {
                    val executor = Executors.newSingleThreadExecutor()
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    var provider: ProcessCameraProvider? = null
                    var analysis: ImageAnalysis? = null
                    providerFuture.addListener(
                        {
                            try {
                                val cameraProvider = providerFuture.get()
                                provider = cameraProvider
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = view.surfaceProvider
                                }
                                val analyzer = QrAnalyzer(onDecoded = { text ->
                                    mainExecutor.execute { onResult(text) }
                                })
                                analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { it.setAnalyzer(executor, analyzer) }
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            } catch (error: Throwable) {
                                cameraError = error.message ?: error.javaClass.simpleName
                            }
                        },
                        mainExecutor,
                    )
                    onDispose {
                        try {
                            analysis?.clearAnalyzer()
                        } catch (_: Throwable) {
                            // The analyzer is gone with the screen.
                        }
                        try {
                            provider?.unbindAll()
                        } catch (_: Throwable) {
                            // Provider already released.
                        }
                        executor.shutdown()
                    }
                }
            }
        }

        // The targeting frame, drawn regardless of the camera state so the
        // screen never flashes a blank black view.
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 140.dp)
        ) {
            val corner = minOf(size.width, size.height) * 0.14f
            val stroke = 3f
            fun cornerLines(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(Crt.Phosphor, Offset(x, y), Offset(x + dx, y), stroke)
                drawLine(Crt.Phosphor, Offset(x, y), Offset(x, y + dy), stroke)
            }
            drawRect(
                color = Crt.Phosphor.copy(alpha = 0.35f),
                style = Stroke(width = 1f),
            )
            cornerLines(0f, 0f, corner, corner)
            cornerLines(size.width, 0f, -corner, corner)
            cornerLines(0f, size.height, corner, -corner)
            cornerLines(size.width, size.height, -corner, -corner)

            val y = size.height * sweep
            drawLine(
                color = Crt.Phosphor.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
            )
            drawRect(
                color = Crt.Phosphor.copy(alpha = 0.04f),
                topLeft = Offset(0f, y - 18f),
                size = Size(size.width, 36f),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.qr_title),
                style = TerminalTextStyle.copy(
                    color = Crt.Phosphor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qr_hint),
                style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!granted) {
                Text(
                    text = stringResource(R.string.qr_camera_denied),
                    style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                TerminalButton(
                    text = stringResource(R.string.button_grant_camera),
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
            cameraError?.let { message ->
                Text(
                    text = "CAMERA ERROR: $message",
                    style = TerminalTextStyle.copy(color = Crt.PhosphorDim, fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            TerminalButton(
                text = stringResource(R.string.button_close),
                onClick = onClose,
                modifier = Modifier.size(width = 180.dp, height = 44.dp),
            )
        }
    }
}
