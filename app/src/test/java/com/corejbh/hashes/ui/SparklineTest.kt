package com.corejbh.hashes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparklineTest {

    @Test
    fun `a constant window is a flat middle line`() {
        val line = Sparkline.render(listOf(5.0, 5.0, 5.0), width = 3)
        assertEquals("\u2584\u2584\u2584", line)
    }

    @Test
    fun `the lowest and highest samples use the end glyphs`() {
        val line = Sparkline.render(listOf(1.0, 2.0, 3.0), width = 3)
        assertEquals('\u2581', line[0])
        assertEquals('\u2588', line[2])
        assertEquals(3, line.length)
    }

    @Test
    fun `the window is left padded so the axis does not move`() {
        val line = Sparkline.render(listOf(1.0, 2.0), width = 6)
        assertEquals(6, line.length)
        assertEquals("\u2581\u2581\u2581\u2581\u2581\u2588", line)
    }

    @Test
    fun `only the newest samples of a long history are drawn`() {
        val values = (0 until 40).map { it.toDouble() }
        val line = Sparkline.render(values, width = 10)
        assertEquals(10, line.length)
        assertEquals('\u2588', line.last())
    }

    @Test
    fun `an empty history is a padded axis`() {
        val line = Sparkline.render(emptyList(), width = 5)
        assertEquals("\u2581\u2581\u2581\u2581\u2581", line)
    }

    @Test
    fun `no width is no line`() {
        assertTrue(Sparkline.render(listOf(1.0), width = 0).isEmpty())
    }
}
