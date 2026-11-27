/*
 * Bitcoin block-header hashing: one midstate, then a nonce loop.
 *
 * An 80-byte header is hashed with double SHA-256, and the first 64 bytes never
 * change while a miner walks the 4-byte nonce. So the SHA-256 state after those
 * 64 bytes (the midstate) is computed once per job, and each nonce then costs
 * two compressions: the second header block (12 fixed header bytes, the 4-byte
 * nonce, padding, length), and the 32-byte digest padded into a block of its own.
 *
 * The job carries the header's first 76 bytes, which is what the bridge passes:
 * ntime and nbits are in there, the nonce is not.
 */
#ifndef HASHES_BLOCK_HEADER_H
#define HASHES_BLOCK_HEADER_H

#include <stdint.h>
#include <string.h>

#include "sha256.h"

#ifdef __cplusplus
extern "C" {
#endif

#define HASHES_HEADER_SIZE 80
#define HASHES_HEADER_PREFIX_SIZE 76
#define HASHES_NONCE_OFFSET 76
#define HASHES_TARGET_SIZE 32

typedef struct {
    uint32_t midstate[8];                   /* state after header bytes 0..63 */
    uint8_t tail[12];                       /* header bytes 64..75: ntime, nbits */
    uint8_t target[HASHES_TARGET_SIZE];     /* big-endian target */
    uint32_t nonce_start;
} hashes_block_job;

/*
 * Prepare a job. `header_prefix` is the header's first 76 bytes, `target` is the
 * big-endian 256-bit target.
 */
void hashes_block_job_init(hashes_block_job *job,
                           const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
                           const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start,
                           hashes_compress_fn compress);

/* Double SHA-256 of the header with this nonce, as 32 big-endian bytes. */
static inline void hashes_block_header_hash(const hashes_block_job *job, uint32_t nonce,
                                            hashes_compress_fn compress,
                                            uint8_t out[HASHES_SHA256_DIGEST_SIZE]) {
    uint32_t state[8];
    memcpy(state, job->midstate, sizeof(state));

    uint8_t block[HASHES_SHA256_BLOCK_SIZE];
    memcpy(block, job->tail, sizeof(job->tail));
    block[12] = (uint8_t)(nonce);
    block[13] = (uint8_t)(nonce >> 8);
    block[14] = (uint8_t)(nonce >> 16);
    block[15] = (uint8_t)(nonce >> 24);
    block[16] = 0x80;
    memset(block + 17, 0, 45); /* bytes 17..61 */
    block[62] = 0x02;          /* 640-bit message, the high byte of the length */
    block[63] = 0x80;          /* ...and its low byte */
    compress(state, block);

    uint8_t first[HASHES_SHA256_BLOCK_SIZE];
    for (int i = 0; i < 8; ++i) {
        first[4 * i + 0] = (uint8_t)(state[i] >> 24);
        first[4 * i + 1] = (uint8_t)(state[i] >> 16);
        first[4 * i + 2] = (uint8_t)(state[i] >> 8);
        first[4 * i + 3] = (uint8_t)(state[i]);
    }
    first[32] = 0x80;
    memset(first + 33, 0, 23); /* bytes 33..55 */
    first[56] = 0x00;
    first[57] = 0x00;
    first[58] = 0x00;
    first[59] = 0x00;
    first[60] = 0x00;
    first[61] = 0x00;
    first[62] = 0x01; /* 256-bit message */
    first[63] = 0x00;

    uint32_t second[8];
    memcpy(second, hashes_sha256_initial_state, sizeof(second));
    compress(second, first);
    for (int i = 0; i < 8; ++i) {
        out[4 * i + 0] = (uint8_t)(second[i] >> 24);
        out[4 * i + 1] = (uint8_t)(second[i] >> 16);
        out[4 * i + 2] = (uint8_t)(second[i] >> 8);
        out[4 * i + 3] = (uint8_t)(second[i]);
    }
}

/*
 * 1 when the hash meets the target.
 *
 * A SHA-256 digest is produced big-endian, but bitcoin's block hash is the same
 * bytes read little-endian - which is why a block is displayed with its zeros in
 * front. So the leading digits of the hash are the *last* digest bytes, and the
 * comparison walks one array forwards and the other backwards.
 *
 * The comparison is "at or below": a hash exactly equal to the target is a valid
 * share (bitcoin's own check is cmp <= 0), so equality is a meet, not a miss.
 */
static inline int hashes_hash_meets_target(const uint8_t hash[HASHES_SHA256_DIGEST_SIZE],
                                           const uint8_t target[HASHES_TARGET_SIZE]) {
    for (int i = 0; i < HASHES_SHA256_DIGEST_SIZE; ++i) {
        uint8_t digit = hash[HASHES_SHA256_DIGEST_SIZE - 1 - i];
        if (digit != target[i]) {
            return digit < target[i];
        }
    }
    return 1;
}

/* Work of a hash: the difficulty-1 target divided by the hash, as a double. */
double hashes_hash_difficulty(const uint8_t hash[HASHES_SHA256_DIGEST_SIZE]);

/* 32 big-endian bytes as a double (the hash is reversed into this before use). */
double hashes_be256_to_double(const uint8_t value[HASHES_SHA256_DIGEST_SIZE]);

#ifdef __cplusplus
}
#endif

#endif /* HASHES_BLOCK_HEADER_H */
