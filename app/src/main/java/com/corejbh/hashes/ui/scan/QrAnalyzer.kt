package com.corejbh.hashes.ui.scan

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX analyzer that hands every frame to ZXing and reports a decoded string
 * once until the code changes. Frames are closed in a finally block, because a
 * leaked ImageProxy stalls the whole analysis pipeline after a handful of frames.
 */
class QrAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val decoder: QrDecoder = QrDecoder(),
) : ImageAnalysis.Analyzer {

    @Volatile
    private var lastText: String? = null

    @Volatile
    private var lastReportedAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val nv21 = Nv21.from(image) ?: return
            val text = decoder.decodeNv21(nv21, image.width, image.height) ?: return
            val now = SystemClock.elapsedRealtime()
            if (text == lastText && now - lastReportedAt < REPEAT_GUARD_MS) return
            lastText = text
            lastReportedAt = now
            onDecoded(text)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val REPEAT_GUARD_MS = 2_000L
    }
}
