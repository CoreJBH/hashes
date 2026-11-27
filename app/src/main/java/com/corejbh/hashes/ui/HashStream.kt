package com.corejbh.hashes.ui

/**
 * The `LIVE_HASH_STREAM` ring buffer.
 *
 * The newest sample is first. A sample whose hex starts with four or more zeros
 * is a near miss worth seeing, and the UI inverts its colours; keeping that
 * decision here makes it testable without Compose.
 */
class HashStream(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Sample(val hex: String, val leadingZeros: Int) {
        val worthHighlighting: Boolean
            get() = leadingZeros >= HIGHLIGHT_ZEROS
    }

    private val samples = ArrayDeque<Sample>(capacity)

    init {
        require(capacity > 0) { "the stream needs at least one line" }
    }

    val size: Int
        get() = samples.size

    /** Adds a hash (display order hex) and returns the current lines, newest first. */
    fun push(hex: String): List<Sample> {
        if (hex.isEmpty()) return lines()
        var zeros = 0
        while (zeros < hex.length && hex[zeros] == '0') zeros++
        samples.addFirst(Sample(hex.lowercase(), zeros))
        while (samples.size > capacity) samples.removeLast()
        return lines()
    }

    fun lines(): List<Sample> = samples.toList()

    fun clear() = samples.clear()

    companion object {
        /**
         * Kept: the live stream panel draws as many lines as the screen has room
         * for, so the buffer holds a full panel on a tall phone.
         */
        const val DEFAULT_CAPACITY = 24
        const val HIGHLIGHT_ZEROS = 4
    }
}
