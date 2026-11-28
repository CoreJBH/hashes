package com.corejbh.hashes.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The default mode is never named. COOL is the absence of TURBO, so it must not
 * appear in a title, a badge or a worker line; only TURBO and the guard that
 * takes it away are words on screen.
 */
class ModeLabelsTest {

    @Test
    fun `the title names turbo only`() {
        assertEquals("HASHES", ModeLabels.title(false))
        assertEquals("HASHES \u00b7 TURBO", ModeLabels.title(true))
        assertFalse(ModeLabels.title(false).contains("COOL"))
    }

    @Test
    fun `the header badge is the temperature alone by default`() {
        assertEquals("38\u00b0C", ModeLabels.thermalBadge(turbo = false, temperatureCelsius = 38.0))
        assertEquals("39\u00b0C", ModeLabels.thermalBadge(turbo = false, temperatureCelsius = 39.4))
    }

    @Test
    fun `the header badge puts turbo in front of the temperature`() {
        assertEquals("TURBO \u00b7 38\u00b0C", ModeLabels.thermalBadge(turbo = true, temperatureCelsius = 38.0))
        assertEquals("TURBO", ModeLabels.thermalBadge(turbo = true, temperatureCelsius = null))
    }

    @Test
    fun `an empty badge is empty, not a word`() {
        assertEquals("", ModeLabels.thermalBadge(turbo = false, temperatureCelsius = null))
    }

    @Test
    fun `the worker line is the thread count by default`() {
        assertEquals(
            "1T",
            ModeLabels.workerTrailer(turbo = false, guard = false, threads = 1, capped = false, threadCap = 1),
        )
        assertEquals(
            "--",
            ModeLabels.workerTrailer(turbo = false, guard = false, threads = 0, capped = false, threadCap = 1),
        )
    }

    @Test
    fun `the worker line puts turbo in front of the thread count`() {
        assertEquals(
            "TURBO 7T",
            ModeLabels.workerTrailer(turbo = true, guard = false, threads = 7, capped = false, threadCap = 7),
        )
    }

    @Test
    fun `a capped turbo says what the cap is`() {
        assertEquals(
            "TURBO 7T / CAP 4T",
            ModeLabels.workerTrailer(turbo = true, guard = false, threads = 7, capped = true, threadCap = 4),
        )
    }

    @Test
    fun `a tripped guard replaces the thread count`() {
        assertEquals(
            "GUARD",
            ModeLabels.workerTrailer(turbo = false, guard = true, threads = 1, capped = false, threadCap = 1),
        )
    }
}
