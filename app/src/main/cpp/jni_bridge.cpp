/*
 * JNI surface of the hash engine: the methods of the bridge contract
 * (docs/native-bridge-contract.md), exported under the names the Kotlin object
 * com.corejbh.hashes.engine.NativeMiner resolves.
 *
 * The names are exported directly rather than registered in JNI_OnLoad, so a
 * missing symbol is a link-time fact instead of a run-time lookup: the library
 * either carries every method the Kotlin side declares, or it does not build.
 *
 * The Kotlin object is a singleton, so the engine is one process-wide instance.
 */
#include <jni.h>

#include <cstdint>
#include <cstring>

#include "miner.h"

namespace {

hashes::NativeMiner &miner() {
    static hashes::NativeMiner instance;
    return instance;
}

/*
 * Copies a fixed-size argument array. Buffers are copied out of the JVM before
 * returning, so the Kotlin side can reuse its arrays between calls.
 */
bool copy_bytes(JNIEnv *env, jbyteArray source, uint8_t *destination, jsize expected) {
    if (source == nullptr || env->GetArrayLength(source) < expected) {
        return false;
    }
    env->GetByteArrayRegion(source, 0, expected, reinterpret_cast<jbyte *>(destination));
    return env->ExceptionCheck() == JNI_FALSE;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeStart(JNIEnv *env, jobject, jbyteArray header_prefix,
                                                       jbyteArray target, jlong nonce_start,
                                                       jboolean turbo, jint duty_hash_micros,
                                                       jint duty_sleep_micros) {
    uint8_t prefix[HASHES_HEADER_PREFIX_SIZE];
    uint8_t target_bytes[HASHES_TARGET_SIZE];
    if (!copy_bytes(env, header_prefix, prefix, HASHES_HEADER_PREFIX_SIZE) ||
        !copy_bytes(env, target, target_bytes, HASHES_TARGET_SIZE)) {
        return JNI_FALSE;
    }
    if (nonce_start < 0 || nonce_start > 0xFFFFFFFFLL) {
        return JNI_FALSE;
    }

    bool started = miner().start(prefix, target_bytes, static_cast<uint32_t>(nonce_start),
                                 turbo == JNI_TRUE, duty_hash_micros, duty_sleep_micros);
    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeUpdateWork(JNIEnv *env, jobject,
                                                            jbyteArray header_prefix,
                                                            jbyteArray target, jlong nonce_start) {
    uint8_t prefix[HASHES_HEADER_PREFIX_SIZE];
    uint8_t target_bytes[HASHES_TARGET_SIZE];
    if (!copy_bytes(env, header_prefix, prefix, HASHES_HEADER_PREFIX_SIZE) ||
        !copy_bytes(env, target, target_bytes, HASHES_TARGET_SIZE)) {
        return JNI_FALSE;
    }
    if (nonce_start < 0 || nonce_start > 0xFFFFFFFFLL) {
        return JNI_FALSE;
    }

    bool updated = miner().update_work(prefix, target_bytes,
                                       static_cast<uint32_t>(nonce_start));
    return updated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeSetTurboMode(JNIEnv *, jobject, jboolean turbo) {
    miner().set_turbo(turbo == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeSetThreadCap(JNIEnv *, jobject, jint threads) {
    miner().set_thread_cap(threads);
}

JNIEXPORT void JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeStop(JNIEnv *, jobject) {
    miner().stop();
}

JNIEXPORT void JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativePollCounters(JNIEnv *env, jobject, jlongArray out) {
    if (out == nullptr || env->GetArrayLength(out) < hashes::NativeMiner::kCounterSlots) {
        return;
    }
    int64_t counters[hashes::NativeMiner::kCounterSlots];
    miner().fill_counters(counters);
    env->SetLongArrayRegion(out, 0, hashes::NativeMiner::kCounterSlots,
                            reinterpret_cast<const jlong *>(counters));
}

/* Per-worker cumulative hashes for the terminal's worker panel. Returns the
 * number of slots filled (the worker count of the current mode); the caller's
 * array is kMaxThreads long, and the rest of it is written as zero. */
JNIEXPORT jint JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativePollThreadHashes(JNIEnv *env, jobject,
                                                                  jlongArray out) {
    if (out == nullptr || env->GetArrayLength(out) < hashes::NativeMiner::kMaxThreads) {
        return 0;
    }
    int64_t counts[hashes::NativeMiner::kMaxThreads];
    int workers = miner().fill_thread_hashes(counts);
    env->SetLongArrayRegion(out, 0, hashes::NativeMiner::kMaxThreads,
                            reinterpret_cast<const jlong *>(counts));
    return workers;
}

JNIEXPORT void JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativePollCurrentHash(JNIEnv *env, jobject,
                                                                 jbyteArray out) {
    if (out == nullptr || env->GetArrayLength(out) < HASHES_SHA256_DIGEST_SIZE) {
        return;
    }
    uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
    miner().current_hash(digest);
    env->SetByteArrayRegion(out, 0, HASHES_SHA256_DIGEST_SIZE,
                            reinterpret_cast<const jbyte *>(digest));
}

JNIEXPORT jboolean JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativePollBestHash(JNIEnv *env, jobject, jbyteArray out) {
    if (out == nullptr || env->GetArrayLength(out) < HASHES_SHA256_DIGEST_SIZE) {
        return JNI_FALSE;
    }
    uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
    if (!miner().best_hash(digest)) {
        return JNI_FALSE;
    }
    env->SetByteArrayRegion(out, 0, HASHES_SHA256_DIGEST_SIZE,
                            reinterpret_cast<const jbyte *>(digest));
    return JNI_TRUE;
}

/*
 * Returns the nonce of the oldest found hash, or -1. The return type is a jlong
 * on purpose: a 32-bit nonce fits in it without colliding with the -1 sentinel
 * when a hash is found at 0xFFFFFFFF, and the low 32 bits are the same value a
 * jint caller would read.
 */
JNIEXPORT jlong JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativePollFoundHash(JNIEnv *env, jobject, jbyteArray out) {
    if (out == nullptr || env->GetArrayLength(out) < HASHES_SHA256_DIGEST_SIZE) {
        return -1;
    }
    uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
    int64_t nonce = miner().take_found_hash(digest);
    if (nonce < 0) {
        return -1;
    }
    env->SetByteArrayRegion(out, 0, HASHES_SHA256_DIGEST_SIZE,
                            reinterpret_cast<const jbyte *>(digest));
    return static_cast<jlong>(nonce);
}

JNIEXPORT jstring JNICALL
Java_com_corejbh_hashes_engine_NativeMiner_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(miner().version());
}

}  // extern "C"
