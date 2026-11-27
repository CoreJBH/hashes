package com.corejbh.hashes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerMeterTest {

    @Test
    fun `rates are the counter difference over the interval`() {
        val rates = WorkerMeter.rates(listOf(1_000L, 2_000L), listOf(3_000L, 2_500L), 2.0)
        assertEquals(listOf(1_000.0, 250.0), rates)
    }

    @Test
    fun `a counter that was reset does not produce a negative rate`() {
        val rates = WorkerMeter.rates(listOf(5_000L), listOf(100L), 1.0)
        assertEquals(listOf(0.0), rates)
    }

    @Test
    fun `a changed worker set is not compared against the old one`() {
        assertEquals(listOf(0.0, 0.0), WorkerMeter.rates(listOf(10L), listOf(20L, 30L), 1.0))
        assertEquals(listOf(0.0), WorkerMeter.rates(emptyList(), listOf(30L), 1.0))
    }

    @Test
    fun `no time is no rate`() {
        assertEquals(listOf(0.0), WorkerMeter.rates(listOf(1L), listOf(2L), 0.0))
    }

    @Test
    fun `the busiest worker draws a full bar and an idle one nothing`() {
        val full = WorkerMeter.bar(500.0, 500.0, cells = 8)
        assertEquals("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588", full)
        assertEquals("\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591", WorkerMeter.bar(0.0, 500.0, cells = 8))
    }

    @Test
    fun `a bar is proportional to the peak and always the same width`() {
        assertEquals("\u2588\u2588\u2588\u2588\u2591\u2591\u2591\u2591", WorkerMeter.bar(50.0, 100.0, cells = 8))
        assertEquals(10, WorkerMeter.bar(3.0, 1_000_000.0).length)
    }

    @Test
    fun `labels stay short`() {
        assertEquals("0", WorkerMeter.label(0.0))
        assertEquals("950", WorkerMeter.label(950.0))
        assertEquals("52.3K", WorkerMeter.label(52_345.0))
        assertEquals("2.4M", WorkerMeter.label(2_412_000.0))
        assertEquals("100K", WorkerMeter.label(99_960.0))
    }
}
