package com.corejbh.hashes.ui.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nv21Test {

    @Test
    fun `copies rows past their stride and interleaves chroma`() {
        // 4x2 image, Y rows of 4 with 2 bytes of padding, chroma 2x1 with
        // pixel stride 2 (U and V interleaved in memory).
        val width = 4
        val height = 2
        val y = byteArrayOf(
            0, 1, 2, 3, 9, 9,
            10, 11, 12, 13, 9, 9,
        )
        val u = byteArrayOf(0x11, 0, 0x33, 0)
        val v = byteArrayOf(0x22, 0, 0x44, 0)

        val nv21 = Nv21.build(
            width = width,
            height = height,
            y = y,
            yRowStride = 6,
            yPixelStride = 1,
            u = u,
            uRowStride = 4,
            uPixelStride = 2,
            v = v,
            vRowStride = 4,
            vPixelStride = 2,
        )

        assertArrayEquals(
            byteArrayOf(
                0, 1, 2, 3, 10, 11, 12, 13,
                0x22, 0x11, 0x44, 0x33,
            ),
            nv21,
        )
    }

    @Test
    fun `handles a contiguous chroma plane with pixel stride one`() {
        val width = 2
        val height = 2
        val nv21 = Nv21.build(
            width = width,
            height = height,
            y = byteArrayOf(1, 2, 3, 4),
            yRowStride = 2,
            yPixelStride = 1,
            u = byteArrayOf(0x11),
            uRowStride = 1,
            uPixelStride = 1,
            v = byteArrayOf(0x22),
            vRowStride = 1,
            vPixelStride = 1,
        )
        assertEquals(2 * 2 * 3 / 2, nv21!!.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0x22, 0x11), nv21)
    }

    @Test
    fun `refuses impossible geometry`() {
        assertNull(
            Nv21.build(
                0, 0, ByteArray(0), 0, 1, ByteArray(0), 0, 1, ByteArray(0), 0, 1,
            )
        )
        assertNull(
            Nv21.build(
                4, 2, ByteArray(4), 4, 1, ByteArray(4), 2, 1, ByteArray(4), 2, 1,
            )
        )
    }
}
