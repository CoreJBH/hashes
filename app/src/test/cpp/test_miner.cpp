/*
 * Behaviour tests for the engine itself: a real start, real worker threads, the
 * counters the bridge polls, the found-hash queue, and the mode and stop paths.
 *
 * Run with scripts/run-native-tests.sh. Uses a target 65536 times easier than
 * difficulty 1 so the run finds hashes quickly enough to test the queue.
 */
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

#include "block_header.h"
#include "miner.h"

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

int main(void) {
    /* Two workers, so the thread paths are exercised on any host. */
    hashes::NativeMiner miner(2);
    printf("version: %s\n", miner.version());

    /* Block 0's header, first 76 bytes: ntime and nbits included, nonce not. */
    uint8_t prefix[HASHES_HEADER_PREFIX_SIZE];
    hex_to_bytes("0100000000000000000000000000000000000000000000000000000000000000"
                 "000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa"
                 "4b1e5e4a29ab5f49ffff001d",
                 prefix, sizeof(prefix));

    /* Two leading zero bytes: one hash in 65536 meets it. */
    uint8_t target[HASHES_TARGET_SIZE];
    memset(target, 0, sizeof(target));
    target[2] = 0xff;

    int64_t counters[hashes::NativeMiner::kCounterSlots];

    CHECK(miner.start(prefix, target, 0, false, 1500, 31000), "start returns true");
    miner.fill_counters(counters);
    CHECK(counters[0] == 1, "state after start is running (got %lld)",
          (long long)counters[0]);
    CHECK(counters[3] == 1, "COOL runs one worker (got %lld)", (long long)counters[3]);

    std::this_thread::sleep_for(std::chrono::milliseconds(700));

    miner.fill_counters(counters);
    CHECK(counters[1] > 0, "hashes counted: %lld", (long long)counters[1]);
    CHECK(counters[2] > 0, "nonce advanced: %lld", (long long)counters[2]);
    CHECK(counters[4] > 0, "hash rate reported: %lld", (long long)counters[4]);

    /* The per-worker totals the terminal's worker panel draws: one slot in
     * COOL, every slot of the set in TURBO, and the slots add up to the job
     * total they were counted into. */
    int64_t worker_hashes[hashes::NativeMiner::kMaxThreads];
    int workers = miner.fill_thread_hashes(worker_hashes);
    CHECK(workers == 1, "COOL reports one worker slot (got %d)", workers);
    CHECK(worker_hashes[0] > 0, "worker 0 counted hashes: %lld",
          (long long)worker_hashes[0]);
    CHECK(worker_hashes[1] == 0, "an idle worker slot reads zero: %lld",
          (long long)worker_hashes[1]);
    int64_t slot_sum = 0;
    for (int index = 0; index < hashes::NativeMiner::kMaxThreads; ++index) {
        slot_sum += worker_hashes[index];
    }
    CHECK(slot_sum == counters[1], "worker slots sum to the job total (%lld vs %lld)",
          (long long)slot_sum, (long long)counters[1]);

    int taken = 0;
    uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
    int64_t nonce = 0;
    while ((nonce = miner.take_found_hash(digest)) >= 0) {
        ++taken;
        CHECK(hashes_hash_meets_target(digest, target) == 1,
              "a found hash does not meet the target");
    }
    CHECK(taken > 0, "found-hash queue was empty after 700 ms (%d)", taken);
    CHECK(miner.take_found_hash(digest) == -1, "an empty queue returns -1");

    CHECK(miner.best_hash(digest) == true, "no best hash after hashing");
    CHECK(hashes_hash_meets_target(digest, target) == 1,
          "the best hash does not meet the target");

    miner.fill_counters(counters);
    CHECK(counters[5] >= 4,
          "best hash should have at least four leading zero nibbles (got %lld)",
          (long long)counters[5]);
    CHECK(counters[7] == 0, "reserved slot is zero");

    /* TURBO uses the whole worker set, COOL goes back to one. */
    miner.set_turbo(true);
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    miner.fill_counters(counters);
    CHECK(counters[3] == miner.max_threads(), "TURBO workers %lld, max threads %d",
          (long long)counters[3], miner.max_threads());
    CHECK(miner.fill_thread_hashes(worker_hashes) == miner.max_threads(),
          "every worker slot is reported in TURBO");

    /* The thermal step between TURBO and COOL: a lower thread cap. */
    if (miner.max_threads() > 1) {
        int half = miner.max_threads() / 2;
        miner.set_thread_cap(std::max(1, half));
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        miner.fill_counters(counters);
        CHECK(counters[3] == std::max(1, half), "capped TURBO workers: %lld",
              (long long)counters[3]);
        miner.set_thread_cap(miner.max_threads());
    }

    miner.set_turbo(false);
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    miner.fill_counters(counters);
    CHECK(counters[3] == 1, "COOL workers after TURBO: %lld", (long long)counters[3]);

    /* A new job while running keeps the engine running and resets the counters. */
    uint8_t other_prefix[HASHES_HEADER_PREFIX_SIZE];
    memcpy(other_prefix, prefix, sizeof(other_prefix));
    other_prefix[68] = 0x01; /* a different nbits, a different midstate */
    CHECK(miner.update_work(other_prefix, target, 0), "update_work returns true");
    miner.fill_counters(counters);
    CHECK(counters[0] == 1, "update_work keeps the engine running");
    CHECK(counters[1] < 100000, "update_work resets the job hash count (%lld)",
          (long long)counters[1]);

    miner.stop();
    miner.fill_counters(counters);
    CHECK(counters[0] == 0, "state after stop is idle (got %lld)", (long long)counters[0]);
    CHECK(counters[3] == 0, "no workers while idle (got %lld)", (long long)counters[3]);

    /* Idempotent, and startable again. */
    miner.stop();
    CHECK(miner.start(prefix, target, 0, true, 0, 0), "restart after stop");
    miner.fill_counters(counters);
    CHECK(counters[0] == 1, "running again");
    miner.stop();

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
