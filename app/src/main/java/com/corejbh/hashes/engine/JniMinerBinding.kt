package com.corejbh.hashes.engine

/**
 * [NativeMinerBinding] over the JNI object in [NativeMiner]; every call is guarded
 * by the library load result, so a build without `libhashes.so` falls through to
 * the Kotlin engine instead of throwing.
 *
 * Declarations live in [NativeMiner] and must match the native symbols in
 * `app/src/main/cpp/jni_bridge.cpp`: `nativePollBestHash` returns a boolean and
 * `nativePollFoundHash` a long nonce (-1 when the queue is empty).
 */
class JniMinerBinding : NativeMinerBinding {

    override val available: Boolean
        get() = NativeMiner.available

    override fun start(
        headerPrefix: ByteArray,
        target: ByteArray,
        nonceStart: Long,
        turbo: Boolean,
        dutyHashMicros: Int,
        dutySleepMicros: Int,
    ): Boolean = available && NativeMiner.nativeStart(
        headerPrefix,
        target,
        nonceStart,
        turbo,
        dutyHashMicros,
        dutySleepMicros,
    )

    override fun updateWork(headerPrefix: ByteArray, target: ByteArray, nonceStart: Long): Boolean =
        available && NativeMiner.nativeUpdateWork(headerPrefix, target, nonceStart)

    override fun setTurboMode(turbo: Boolean) {
        if (available) NativeMiner.nativeSetTurboMode(turbo)
    }

    override fun setThreadCap(threads: Int) {
        if (available) NativeMiner.nativeSetThreadCap(threads)
    }

    override fun stop() {
        if (available) NativeMiner.nativeStop()
    }

    override fun pollCounters(out: LongArray) {
        if (available) NativeMiner.nativePollCounters(out)
    }

    override fun pollCurrentHash(out: ByteArray) {
        if (available) NativeMiner.nativePollCurrentHash(out)
    }

    override fun pollBestHash(out: ByteArray): Boolean =
        available && NativeMiner.nativePollBestHash(out)

    override fun pollFoundHash(out: ByteArray): Long =
        if (available) NativeMiner.nativePollFoundHash(out) else -1L

    override fun version(): String =
        if (!available) {
            UNAVAILABLE
        } else {
            try {
                NativeMiner.nativeVersion()
            } catch (_: UnsatisfiedLinkError) {
                UNAVAILABLE
            }
        }

    private companion object {
        const val UNAVAILABLE = "unavailable"
    }
}
