/*
 * SHA-256 compression using the ARMv8 SHA-2 instructions.
 *
 * THIS FILE IS COMPILED WITH -march=armv8-a+crypto ON ITS OWN (see
 * CMakeLists.txt) and is only added to the build for arm64-v8a. Nothing else in
 * the project gets that flag, so a CPU without the SHA2 extension never reaches
 * an instruction it cannot decode: the dispatch in sha256_dispatch.c binds to
 * this function only when getauxval(AT_HWCAP) reports HWCAP_SHA2.
 *
 * The four-round grouping (SHA256H / SHA256H2 / SHA256SU0 / SHA256SU1 with the
 * round constant added to the message words) is the standard way these
 * instructions are driven; it produces exactly the FIPS 180-4 compression, and
 * the host test compares it against the portable C version for every block the
 * test hashes.
 */
#include "sha256.h"

#if !defined(__aarch64__)
#error "sha256_arm64_crypto.c must only be compiled for arm64-v8a"
#elif !defined(__ARM_FEATURE_CRYPTO)
#error "sha256_arm64_crypto.c must be compiled with -march=armv8-a+crypto"
#endif

#include <arm_neon.h>

static const uint32_t k[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
};

void hashes_sha256_compress_arm64_crypto(uint32_t state[8], const uint8_t block[64]) {
    uint32x4_t state0 = vld1q_u32(&state[0]); /* a b c d */
    uint32x4_t state1 = vld1q_u32(&state[4]); /* e f g h */
    const uint32x4_t saved0 = state0;
    const uint32x4_t saved1 = state1;

    uint32x4_t msg0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 0)));
    uint32x4_t msg1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 16)));
    uint32x4_t msg2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 32)));
    uint32x4_t msg3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block + 48)));

    uint32x4_t tmp0 = vaddq_u32(msg0, vld1q_u32(&k[0]));
    uint32x4_t tmp1;
    uint32x4_t tmp2;

    /* Rounds 0-3 */
    msg0 = vsha256su0q_u32(msg0, msg1);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg1, vld1q_u32(&k[4]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg0 = vsha256su1q_u32(msg0, msg2, msg3);

    /* Rounds 4-7 */
    msg1 = vsha256su0q_u32(msg1, msg2);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg2, vld1q_u32(&k[8]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg1 = vsha256su1q_u32(msg1, msg3, msg0);

    /* Rounds 8-11 */
    msg2 = vsha256su0q_u32(msg2, msg3);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg3, vld1q_u32(&k[12]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg2 = vsha256su1q_u32(msg2, msg0, msg1);

    /* Rounds 12-15 */
    msg3 = vsha256su0q_u32(msg3, msg0);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg0, vld1q_u32(&k[16]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg3 = vsha256su1q_u32(msg3, msg1, msg2);

    /* Rounds 16-19 */
    msg0 = vsha256su0q_u32(msg0, msg1);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg1, vld1q_u32(&k[20]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg0 = vsha256su1q_u32(msg0, msg2, msg3);

    /* Rounds 20-23 */
    msg1 = vsha256su0q_u32(msg1, msg2);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg2, vld1q_u32(&k[24]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg1 = vsha256su1q_u32(msg1, msg3, msg0);

    /* Rounds 24-27 */
    msg2 = vsha256su0q_u32(msg2, msg3);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg3, vld1q_u32(&k[28]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg2 = vsha256su1q_u32(msg2, msg0, msg1);

    /* Rounds 28-31 */
    msg3 = vsha256su0q_u32(msg3, msg0);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg0, vld1q_u32(&k[32]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg3 = vsha256su1q_u32(msg3, msg1, msg2);

    /* Rounds 32-35 */
    msg0 = vsha256su0q_u32(msg0, msg1);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg1, vld1q_u32(&k[36]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg0 = vsha256su1q_u32(msg0, msg2, msg3);

    /* Rounds 36-39 */
    msg1 = vsha256su0q_u32(msg1, msg2);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg2, vld1q_u32(&k[40]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg1 = vsha256su1q_u32(msg1, msg3, msg0);

    /* Rounds 40-43 */
    msg2 = vsha256su0q_u32(msg2, msg3);
    tmp2 = state0;
    tmp1 = vaddq_u32(msg3, vld1q_u32(&k[44]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);
    msg2 = vsha256su1q_u32(msg2, msg0, msg1);

    /* Rounds 44-47 */
    msg3 = vsha256su0q_u32(msg3, msg0);
    tmp2 = state0;
    tmp0 = vaddq_u32(msg0, vld1q_u32(&k[48]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);
    msg3 = vsha256su1q_u32(msg3, msg1, msg2);

    /* Rounds 48-51 */
    tmp2 = state0;
    tmp1 = vaddq_u32(msg1, vld1q_u32(&k[52]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);

    /* Rounds 52-55 */
    tmp2 = state0;
    tmp0 = vaddq_u32(msg2, vld1q_u32(&k[56]));
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);

    /* Rounds 56-59 */
    tmp2 = state0;
    tmp1 = vaddq_u32(msg3, vld1q_u32(&k[60]));
    state0 = vsha256hq_u32(state0, state1, tmp0);
    state1 = vsha256h2q_u32(state1, tmp2, tmp0);

    /* Rounds 60-63 */
    tmp2 = state0;
    state0 = vsha256hq_u32(state0, state1, tmp1);
    state1 = vsha256h2q_u32(state1, tmp2, tmp1);

    state0 = vaddq_u32(state0, saved0);
    state1 = vaddq_u32(state1, saved1);

    vst1q_u32(&state[0], state0);
    vst1q_u32(&state[4], state1);
}
