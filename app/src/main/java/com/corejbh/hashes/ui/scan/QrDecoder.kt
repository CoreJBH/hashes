package com.corejbh.hashes.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * QR decoding through ZXing core, the pure-Java library F-Droid can build.
 *
 * ML Kit and Google Play Services are deliberately not used: they cannot be
 * rebuilt from source and would make the app non-free as well as unusable on a
 * de-Googled device. ZXing reads the luminance plane directly, so a frame costs
 * one binarisation and one decode and nothing is uploaded.
 */
class QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }

    /** Decodes a QR code from a full-frame NV21 image. Returns null when none is found. */
    fun decodeNv21(nv21: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (nv21.size < width * height) return null
        return decodeLuminance(nv21, width, height)
    }

    /** Decodes a QR code from a plain 8-bit luminance buffer. */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
