package com.corejbh.hashes.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body's height division: fill exactly when the screen can hold it, keep the
 * floors when it cannot so the column scrolls instead of clipping.
 */
class TerminalLayoutTest {

    @Test
    fun `the stream and the log take exactly what the fixed rows leave`() {
        val fixed = 400.dp + TerminalLayout.DIVIDER
        val viewport = 800.dp
        val flexible = TerminalLayout.flexibleHeights(viewport, fixed)

        assertEquals(viewport.value, (fixed + flexible.stream + flexible.log).value, 0.5f)
        assertTrue(flexible.stream > flexible.log)
    }

    @Test
    fun `an overfull screen keeps both floors so the body scrolls`() {
        val fixed = 700.dp
        val viewport = 500.dp
        val flexible = TerminalLayout.flexibleHeights(viewport, fixed)

        assertEquals(TerminalLayout.MIN_STREAM, flexible.stream)
        assertEquals(TerminalLayout.MIN_LOG, flexible.log)
        assertTrue(fixed + flexible.stream + flexible.log > viewport)
    }

    @Test
    fun `a leftover smaller than the floors is floored, not squeezed`() {
        val fixed = 540.dp
        val viewport = 620.dp
        val flexible = TerminalLayout.flexibleHeights(viewport, fixed)

        assertEquals(TerminalLayout.MIN_STREAM, flexible.stream)
        assertEquals(TerminalLayout.MIN_LOG, flexible.log)
        assertTrue(fixed + flexible.stream + flexible.log > viewport)
    }

    @Test
    fun `the floors are sizes a panel reads at`() {
        assertTrue(TerminalLayout.MIN_STREAM > 0.dp)
        assertTrue(TerminalLayout.MIN_LOG > 0.dp)
        assertTrue(TerminalLayout.DIVIDER > 0.dp)
    }
}
