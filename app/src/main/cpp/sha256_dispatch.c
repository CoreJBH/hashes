/*
 * Compression dispatch: ARMv8 SHA2 when the CPU has it, portable C otherwise.
 *
 * The decision is made once, at the first hash, from the kernel's auxiliary
 * vector (AT_HWCAP / HWCAP_SHA2) - not from the ABI, not from a build flag.
 *
 * This is what keeps a device without the SHA2 extension from executing an
 * instruction it cannot decode: sha256_arm64_crypto.c is compiled with
 * -march=armv8-a+crypto, and its function is reachable only through this
 * pointer, which is bound to it only when HWCAP says the extension exists.
 * Every other translation unit is compiled for plain armv8-a, so a SIGILL can
 * only come from a compiler that emitted such an instruction where it must not,
 * which the ordered build flags rule out.
 */
#include "sha256.h"

#include <stdatomic.h>

#if defined(__aarch64__)
#include <sys/auxv.h>
#if defined(__has_include)
#if __has_include(<asm/hwcap.h>)
#include <asm/hwcap.h>
#endif
#endif
/* From arch/arm64/include/uapi/asm/hwcap.h; repeated here so a libc that does
 * not ship the header still compiles the same check. */
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1UL << 6)
#endif
#ifndef AT_HWCAP
#define AT_HWCAP 16
#endif
#endif

static _Atomic(hashes_compress_fn) selected_compress = NULL;

static hashes_compress_fn resolve_compress(void) {
#if defined(HASHES_HAVE_ARM64_SHA2) && defined(__aarch64__)
    unsigned long capabilities = getauxval(AT_HWCAP);
    if ((capabilities & HWCAP_SHA2) != 0UL) {
        return hashes_sha256_compress_arm64_crypto;
    }
#endif
    return hashes_sha256_compress_portable;
}

hashes_compress_fn hashes_sha256_selected_compress(void) {
    hashes_compress_fn resolved = atomic_load_explicit(&selected_compress, memory_order_acquire);
    if (resolved == NULL) {
        resolved = resolve_compress();
        atomic_store_explicit(&selected_compress, resolved, memory_order_release);
    }
    return resolved;
}

const char *hashes_sha256_backend_name(void) {
#if defined(HASHES_HAVE_ARM64_SHA2) && defined(__aarch64__)
    if (hashes_sha256_selected_compress() == hashes_sha256_compress_arm64_crypto) {
        return "armv8-sha2";
    }
#endif
    return "portable-c";
}
