package com.corejbh.hashes.network

/**
 * Exponential reconnect backoff: 2 s, 4 s, 8 s, 16 s, 32 s, then 60 s for every
 * further attempt, as agreed in the design document. A successful connection
 * resets it, so a dropped line does not leave the next reconnect slow.
 */
class Backoff(
    private val initialMs: Long = 2_000,
    private val maxMs: Long = 60_000,
    private val multiplier: Int = 2,
) {
    var attempts: Int = 0
        private set

    private var nextMs: Long = initialMs

    fun nextDelayMs(): Long {
        val delay = nextMs
        attempts += 1
        nextMs = if (delay >= maxMs / multiplier) maxMs else delay * multiplier
        return delay
    }

    fun reset() {
        attempts = 0
        nextMs = initialMs
    }
}
