#include "block_header.h"

#include <math.h>

void hashes_block_job_init(hashes_block_job *job,
                           const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
                           const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start,
                           hashes_compress_fn compress) {
    uint32_t state[8];
    memcpy(state, hashes_sha256_initial_state, sizeof(state));

    /* The first 64 bytes never change during a job, so their state is computed
     * once here and reused by every nonce. */
    uint8_t first_block[HASHES_SHA256_BLOCK_SIZE];
    memcpy(first_block, header_prefix, sizeof(first_block));
    compress(state, first_block);

    memcpy(job->midstate, state, sizeof(job->midstate));
    memcpy(job->tail, header_prefix + HASHES_SHA256_BLOCK_SIZE, sizeof(job->tail));
    memcpy(job->target, target, HASHES_TARGET_SIZE);
    job->nonce_start = nonce_start;
}

double hashes_be256_to_double(const uint8_t value[HASHES_SHA256_DIGEST_SIZE]) {
    double result = 0.0;
    for (int i = 0; i < HASHES_SHA256_DIGEST_SIZE; ++i) {
        result = result * 256.0 + (double)value[i];
    }
    return result;
}

double hashes_hash_difficulty(const uint8_t hash[HASHES_SHA256_DIGEST_SIZE]) {
    /* The block hash reads the digest little-endian; reverse it so the leading
     * digits are the leading bytes of the number. */
    uint8_t reversed[HASHES_SHA256_DIGEST_SIZE];
    for (int i = 0; i < HASHES_SHA256_DIGEST_SIZE; ++i) {
        reversed[i] = hash[HASHES_SHA256_DIGEST_SIZE - 1 - i];
    }
    double value = hashes_be256_to_double(reversed);
    if (value <= 0.0) {
        return 0.0;
    }
    /* Difficulty 1 is the target 0x00000000FFFF0000...0000, i.e. 0xFFFF * 2^208.
     * A share's work is that target divided by its hash. */
    return ldexp(65535.0, 208) / value;
}
