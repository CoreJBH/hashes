package com.corejbh.hashes.core

/**
 * How the engine spends the CPU.
 *
 * [COOL] is the default: one worker and a duty cycle tied to the frame rate, so
 * the phone stays cool and the screen stays smooth. [TURBO] is the explicit
 * opt-in: every worker thread at background priority, with the thermal monitor
 * allowed to turn it back off.
 */
enum class MinerMode {
    COOL,
    TURBO,
}
