package com.corejbh.hashes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.corejbh.hashes.R
import com.corejbh.hashes.ui.theme.Crt
import com.corejbh.hashes.ui.theme.crtScanlines
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The launch screen: the app mark floating on its own glow, the name, one line
 * saying what the app does, and a CRT that is still settling down.
 *
 * The effects are the point of it: scanlines over everything, a bright scan band
 * sweeping once, horizontal jitter with fractional-pixel offsets and a fast alpha
 * flicker. They run for [SPLASH_MILLIS] and the app crossfades into the terminal,
 * so the noise reads as a monitor warming up rather than a loading spinner.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    var jitter by remember { mutableStateOf(0f) }
    var glitchSeed by remember { mutableStateOf(0) }

    val transition = rememberInfiniteTransition(label = "splash-crt")
    val flicker by transition.animateFloat(
        initialValue = 0.80f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 70, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker",
    )
    val sweep by transition.animateFloat(
        initialValue = -0.1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    LaunchedEffect(Unit) {
        while (true) {
            // Fractional offsets are what makes it read as an analogue signal
            // rather than a moving box.
            jitter = (Random.nextFloat() - 0.5f) * 3.2f
            glitchSeed = Random.nextInt(0, 40)
            delay(Random.nextLong(55, 140))
        }
    }
    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        onFinished()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Crt.Background)
            .crtScanlines(alpha = 0.09f)
    ) {
        // The glow behind the mark and the moving scan band.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height * 0.40f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Crt.Glow, Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.42f,
                        ),
                        radius = size.minDimension * 0.42f,
                        center = center,
                    )
                    val bandY = size.height * sweep
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Crt.Phosphor.copy(alpha = 0.10f), Color.Transparent),
                            startY = bandY - 40f,
                            endY = bandY + 40f,
                        ),
                        topLeft = Offset(0f, bandY - 40f),
                        size = androidx.compose.ui.geometry.Size(size.width, 80f),
                    )
                    // Fragmented horizontal glitch bars.
                    repeat(3) { index ->
                        val y = size.height * (0.30f + 0.18f * index) + glitchSeed
                        val height = 1.5f + index
                        drawRect(
                            color = Crt.Phosphor.copy(alpha = 0.06f),
                            topLeft = Offset(0f, y),
                            size = androidx.compose.ui.geometry.Size(size.width, height),
                        )
                    }
                }
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(136.dp)
                    .graphicsLayer {
                        translationX = jitter
                        alpha = flicker
                    }
                    .clip(RoundedCornerShape(30.dp))
                    .background(Crt.Background)
                    .border(1.dp, Crt.Phosphor.copy(alpha = 0.35f), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(136.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(Modifier.height(34.dp))

            Text(
                text = stringResource(R.string.splash_title),
                color = Crt.Phosphor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 38.sp,
                letterSpacing = 12.sp,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = jitter
                        alpha = flicker
                    }
                    .padding(start = 12.dp),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.splash_subtitle),
                color = Crt.PhosphorDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 2.2.sp,
                modifier = Modifier.graphicsLayer { alpha = flicker * 0.9f },
            )
        }
    }
}

const val SPLASH_MILLIS = 1_400L
