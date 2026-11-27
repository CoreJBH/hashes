/*
 * SHA-256 primitives for the Hashes hash engine.
 *
 * The compression function is the only part that is accelerated. Everything
 * else (padding, the double hash of a block header, the dispatch) is plain C so
 * the same code runs in the app, in the host test and in the emulated ARM test.
 */
#ifndef HASHES_SHA256_H
#define HASHES_SHA256_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HASHES_SHA256_BLOCK_SIZE 64
#define HASHES_SHA256_DIGEST_SIZE 32

/* One compression: absorb a single 64-byte block into an eight-word state. */
typedef void (*hashes_compress_fn)(uint32_t state[8], const uint8_t block[64]);

/* The SHA-256 initial hash value (FIPS 180-4, 5.3.3). */
extern const uint32_t hashes_sha256_initial_state[8];

/* Portable C compression, correct on every ABI. */
void hashes_sha256_compress_portable(uint32_t state[8], const uint8_t block[64]);

#if defined(HASHES_HAVE_ARM64_SHA2)
/*
 * Compression built from the ARMv8 SHA2 instructions. Only this translation
 * unit is compiled with -march=armv8-a+crypto, so a device without the
 * extension never executes one of these instructions.
 */
void hashes_sha256_compress_arm64_crypto(uint32_t state[8], const uint8_t block[64]);
#endif

/*
 * The compression to use on this device: the ARMv8 SHA2 path when the CPU
 * reports HWCAP_SHA2, the portable path otherwise. Resolved once and then
 * loaded, so the per-hash cost is a pointer load.
 */
hashes_compress_fn hashes_sha256_selected_compress(void);

/* "armv8-sha2" or "portable-c": what hashes_sha256_selected_compress() resolved to. */
const char *hashes_sha256_backend_name(void);

/* Single SHA-256 of an arbitrary message length, used by tests and the header code. */
void hashes_sha256_hash(const uint8_t *data, size_t length, uint8_t out[32],
                        hashes_compress_fn compress);

#ifdef __cplusplus
}
#endif

#endif /* HASHES_SHA256_H */
