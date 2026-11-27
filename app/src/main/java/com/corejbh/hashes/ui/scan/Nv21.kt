package com.corejbh.hashes.ui.scan

import androidx.camera.core.ImageProxy

/**
 * Converts a CameraX YUV_420_888 frame into an NV21 byte array, which is the one
 * layout ZXing's luminance source reads without a colour conversion library.
 *
 * CameraX hands back three planes with their own row and pixel strides. Devices
 * differ: some give a pixel stride of 1 for chroma, some interleave U and V with a
 * stride of 2. The conversion walks the strides rather than assuming either, so a
 * scan is not silently half-black on one vendor's camera.
 */
object Nv21 {

    fun from(image: ImageProxy): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        return build(
            width = image.width,
            height = image.height,
            y = copy(yPlane.buffer),
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            u = copy(uPlane.buffer),
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            v = copy(vPlane.buffer),
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
        )
    }

    fun build(
        width: Int,
        height: Int,
        y: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        u: ByteArray,
        uRowStride: Int,
        uPixelStride: Int,
        v: ByteArray,
        vRowStride: Int,
        vPixelStride: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0) return null
        val out = ByteArray(width * height * 3 / 2)
        var index = 0
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                val source = row * yRowStride + column * yPixelStride
                if (source >= y.size) return null
                out[index++] = y[source]
                column += 1
            }
        }
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val uIndex = row * uRowStride + column * uPixelStride
                val vIndex = row * vRowStride + column * vPixelStride
                if (uIndex >= u.size || vIndex >= v.size) return null
                out[index++] = v[vIndex]
                out[index++] = u[uIndex]
            }
        }
        return out
    }

    private fun copy(buffer: java.nio.ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }
}
