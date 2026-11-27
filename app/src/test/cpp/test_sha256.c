/*
 * Host-side tests for the hash engine.
 *
 * Run with scripts/run-native-tests.sh. The same file is compiled for the host
 * (portable compression only) and, by scripts/run-native-tests-aarch64.sh, for
 * arm64 with the SHA2 extension so the accelerated path is compared against the
 * portable one instruction for instruction.
 *
 * Every vector here is a real bitcoin block header: the genesis block, block 1
 * and block 170000, whose double SHA-256 is public and fixed.
 */
#include <stdio.h>
#include <string.h>

#include "block_header.h"
#include "sha256.h"

static int failures = 0;
static int checks = 0;

#define CHECK(condition, ...)                       \
    do {                                            \
        ++checks;                                   \
        if (!(condition)) {                         \
            ++failures;                             \
            printf("FAIL: ");                       \
            printf(__VA_ARGS__);                    \
            printf("\n");                           \
        }                                           \
    } while (0)

static void hex_to_bytes(const char *hex, uint8_t *out, size_t out_len) {
    for (size_t i = 0; i < out_len; ++i) {
        unsigned int byte = 0;
        sscanf(hex + 2 * i, "%2x", &byte);
        out[i] = (uint8_t)byte;
    }
}

static void bytes_to_hex(const uint8_t *bytes, size_t length, char *out) {
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < length; ++i) {
        out[2 * i] = digits[bytes[i] >> 4];
        out[2 * i + 1] = digits[bytes[i] & 0x0f];
    }
    out[2 * length] = '\0';
}

/* The reversed digest, which is how a block hash is written down. */
static void hash_to_display(const uint8_t digest[32], char *out) {
    uint8_t reversed[32];
    for (int i = 0; i < 32; ++i) {
        reversed[i] = digest[31 - i];
    }
    bytes_to_hex(reversed, 32, out);
}

static void target_from_bits(uint32_t bits, uint8_t target[32]) {
    memset(target, 0, 32);
    int exponent = (int)(bits >> 24);
    uint32_t mantissa = bits & 0x007fffffu;
    int start = 32 - exponent;
    target[start] = (uint8_t)(mantissa >> 16);
    target[start + 1] = (uint8_t)(mantissa >> 8);
    target[start + 2] = (uint8_t)mantissa;
}

static void test_sha256_vectors(void) {
    hashes_compress_fn compress = hashes_sha256_selected_compress();
    uint8_t digest[32];
    char hex[65];

    hashes_sha256_hash((const uint8_t *)"abc", 3, digest, compress);
    /* For a plain message the digest is printed in its own byte order. */
    bytes_to_hex(digest, 32, hex);
    CHECK(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0,
          "sha256(\"abc\") = %s", hex);

    hashes_sha256_hash((const uint8_t *)"", 0, digest, compress);
    bytes_to_hex(digest, 32, hex);
    CHECK(strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0,
          "sha256(\"\") = %s", hex);

    const char *two_block = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    hashes_sha256_hash((const uint8_t *)two_block, strlen(two_block), digest, compress);
    bytes_to_hex(digest, 32, hex);
    CHECK(strcmp(hex, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1") == 0,
          "sha256(56-byte message) = %s", hex);

    /* 1,000,000 'a': many full blocks plus a padded tail. */
    static uint8_t million[1000000];
    memset(million, 'a', sizeof(million));
    hashes_sha256_hash(million, sizeof(million), digest, compress);
    bytes_to_hex(digest, 32, hex);
    CHECK(strcmp(hex, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0") == 0,
          "sha256(1e6 * 'a') = %s", hex);
}

typedef struct {
    const char *name;
    const char *header_hex;
    const char *hash_hex; /* display order */
    uint32_t bits;
} header_vector;

static void test_block_headers(void) {
    static const header_vector vectors[] = {
        {"genesis", "0100000000000000000000000000000000000000000000000000000000000000"
                    "000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa"
                    "4b1e5e4a29ab5f49ffff001d1dac2b7c",
         "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 0x1d00ffffu},
        {"block 1",
         "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4"
         "ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299",
         "00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048", 0x1d00ffffu},
        {"block 170000",
         "01000000337b169fca6d6e3a0872ccb84489c86f877077f9208540856b090000000000002b8e6abbeb"
         "371d970fe46b341d57c1fe5d5f6fab97157b5d89f3a2a9240f59ad7519574f0c350b1a2389a326",
         "000000000000051f68f43e9d455e72d9c4e4ce52e8a00c5e24c07340632405cb", 0x1a0c4f0cu},
    };

    hashes_compress_fn compress = hashes_sha256_selected_compress();

    for (size_t i = 0; i < sizeof(vectors) / sizeof(vectors[0]); ++i) {
        const header_vector *vector = &vectors[i];
        uint8_t header[HASHES_HEADER_SIZE];
        uint8_t target[HASHES_TARGET_SIZE];
        hex_to_bytes(vector->header_hex, header, sizeof(header));
        target_from_bits(vector->bits, target);

        /* Double SHA-256 of the whole header, the straightforward way. */
        uint8_t first[32];
        uint8_t double_hash[32];
        hashes_sha256_hash(header, sizeof(header), first, compress);
        hashes_sha256_hash(first, sizeof(first), double_hash, compress);
        char display[65];
        hash_to_display(double_hash, display);
        CHECK(strcmp(display, vector->hash_hex) == 0, "%s: double sha256 = %s", vector->name,
              display);

        /* The same header through the midstate path with the header's own nonce. */
        uint32_t nonce = (uint32_t)header[76] | ((uint32_t)header[77] << 8) |
                         ((uint32_t)header[78] << 16) | ((uint32_t)header[79] << 24);
        hashes_block_job job;
        hashes_block_job_init(&job, header, target, nonce, compress);
        uint8_t from_job[32];
        hashes_block_header_hash(&job, nonce, compress, from_job);
        CHECK(memcmp(from_job, double_hash, sizeof(from_job)) == 0,
              "%s: midstate path differs from a full double hash", vector->name);

        CHECK(hashes_hash_meets_target(from_job, target) == 1,
              "%s: a mined header does not meet its own target", vector->name);

        double work = hashes_hash_difficulty(from_job);
        CHECK(work > 1.0, "%s: work of a mined header is %.6f", vector->name, work);
    }
}

static void test_difficulty_one(void) {
    /* The difficulty-1 target itself has exactly difficulty 1. */
    uint8_t target[32];
    target_from_bits(0x1d00ffffu, target);
    uint8_t hash[32];
    for (int i = 0; i < 32; ++i) {
        hash[i] = target[31 - i];
    }
    double difficulty = hashes_hash_difficulty(hash);
    CHECK(difficulty > 0.999999 && difficulty < 1.000001, "difficulty-1 target = %.9f",
          difficulty);
}

/* A job whose target is met should be reported by the nonce walk; a job whose
 * target is impossible should not. This exercises the comparison and the range. */
static void test_nonce_walk(void) {
    uint8_t header[HASHES_HEADER_SIZE];
    hex_to_bytes("0100000000000000000000000000000000000000000000000000000000000000"
                 "000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa"
                 "4b1e5e4a29ab5f49ffff001d00000000",
                 header, sizeof(header));
    hashes_compress_fn compress = hashes_sha256_selected_compress();
    /* An easier target than any real block: two leading zero bytes, so a walk of
     * a couple of million nonces meets it about thirty times instead of never
     * (mainnet difficulty 1 is one in 2^32). */
    uint8_t target[HASHES_TARGET_SIZE];
    memset(target, 0, sizeof(target));
    target[2] = 0xff;

    hashes_block_job job;
    hashes_block_job_init(&job, header, target, 0, compress);

    int found = 0;
    for (uint32_t nonce = 0; nonce < 2000000; ++nonce) {
        uint8_t digest[32];
        hashes_block_header_hash(&job, nonce, compress, digest);
        if (hashes_hash_meets_target(digest, target)) {
            ++found;
        }
    }
    /* A 16-bit-zero target is met about once in 2^16 hashes, so a 2,000,000-nonce
     * walk should find about thirty. A zero result means the comparison never
     * matches; a result in the thousands means it matches almost everything. */
    CHECK(found >= 5 && found <= 100, "nonce walk over 2000000 nonces found %d shares", found);
}

#if defined(HASHES_HAVE_ARM64_SHA2)
static void test_arm_crypto_matches_portable(void) {
    /* Every block of a padded 200-byte message plus the bitcoin vectors: the
     * accelerated compression must produce the portable state exactly. */
    static uint8_t message[256];
    for (size_t i = 0; i < sizeof(message); ++i) {
        message[i] = (uint8_t)(i * 37u + 11u);
    }
    for (size_t offset = 0; offset + 64 <= sizeof(message); offset += 64) {
        uint32_t portable[8];
        uint32_t crypto[8];
        memcpy(portable, hashes_sha256_initial_state, sizeof(portable));
        memcpy(crypto, hashes_sha256_initial_state, sizeof(crypto));
        hashes_sha256_compress_portable(portable, message + offset);
        hashes_sha256_compress_arm64_crypto(crypto, message + offset);
        CHECK(memcmp(portable, crypto, sizeof(portable)) == 0,
              "armv8 sha2 compression differs at offset %zu", offset);
    }

    /* And on the real headers, through the whole double hash. */
    uint8_t header[HASHES_HEADER_SIZE];
    hex_to_bytes("01000000337b169fca6d6e3a0872ccb84489c86f877077f9208540856b090000000000002b8e"
                 "6abbeb371d970fe46b341d57c1fe5d5f6fab97157b5d89f3a2a9240f59ad7519574f0c350b1a2389"
                 "a326",
                 header, sizeof(header));
    uint8_t target[32];
    target_from_bits(0x1a0c4f0cu, target);

    hashes_block_job portable_job;
    hashes_block_job crypto_job;
    hashes_block_job_init(&portable_job, header, target, 0, hashes_sha256_compress_portable);
    hashes_block_job_init(&crypto_job, header, target, 0, hashes_sha256_compress_arm64_crypto);

    uint8_t portable_digest[32];
    uint8_t crypto_digest[32];
    hashes_block_header_hash(&portable_job, 12345, hashes_sha256_compress_portable,
                             portable_digest);
    hashes_block_header_hash(&crypto_job, 12345, hashes_sha256_compress_arm64_crypto,
                             crypto_digest);
    CHECK(memcmp(portable_digest, crypto_digest, sizeof(portable_digest)) == 0,
          "armv8 sha2 double hash differs from portable");
}
#endif

int main(void) {
    printf("backend: %s\n", hashes_sha256_backend_name());

    test_sha256_vectors();
    test_block_headers();
    test_difficulty_one();
    test_nonce_walk();
#if defined(HASHES_HAVE_ARM64_SHA2)
    test_arm_crypto_matches_portable();
#endif

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
