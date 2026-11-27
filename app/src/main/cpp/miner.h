/*
 * The in-app miner: worker threads that walk nonces, and one snapshot the UI
 * reads.
 *
 * The Kotlin side never calls into the hash loop. It hands over a job once, asks
 * for counters about thirty times a second, and takes found hashes out of a
 * small queue; every counter the workers touch is atomic, so a snapshot is
 * consistent without stopping anything.
 *
 * The method set is the bridge contract in docs/native-bridge-contract.md:
 * start, updateWork, setTurboMode, stop, and the three polls.
 */
#ifndef HASHES_MINER_H
#define HASHES_MINER_H

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "block_header.h"

namespace hashes {

/** The state a poll reports in slot 0. */
enum class State : int {
    Idle = 0,
    Running = 1,
    Stopping = 2,
    Error = 3,
};

/** What fill_counters() writes, one field per slot of the jlong[8]. */
struct Counters {
    State state;
    uint64_t hashes;
    uint32_t nonce;
    int threads;
    double hash_rate;
    int best_leading_zero_nibbles;
    bool hardware_sha2;
};

struct FoundHash {
    uint32_t nonce;
    uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
};

class NativeMiner {
public:
    /** Slot count of the counters array the bridge fills. */
    static constexpr int kCounterSlots = 8;
    /** Worker slots the per-thread counter array can report. */
    static constexpr int kMaxThreads = 8;
    /** Found hashes kept for the poll; the oldest is dropped when it is full. */
    static constexpr size_t kFoundQueueSize = 8;

    /* worker_limit 0 means the device's own limit (cores minus one, at most
     * eight); a test passes a number so the thread paths can be exercised on a
     * machine with few cores. */
    explicit NativeMiner(int worker_limit = 0);
    ~NativeMiner();

    NativeMiner(const NativeMiner &) = delete;
    NativeMiner &operator=(const NativeMiner &) = delete;

    /*
     * Start a job. `header_prefix` is the first 76 bytes of the 80-byte header
     * (the 4-byte nonce is written by the core), `target` is big-endian.
     * `duty_hash_micros` / `duty_sleep_micros` are the COOL duty cycle; zero
     * means hash continuously. Stops any previous run first. Returns false only
     * when the workers could not be created.
     */
    bool start(const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
               const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start, bool turbo,
               int duty_hash_micros, int duty_sleep_micros);

    /* Swap the job under the running workers: midstate once, nonce counter
     * reset, threads kept. */
    bool update_work(const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
                     const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start);

    /* COOL (one thread, duty cycle) or TURBO (all workers, background
     * priority). Safe before start; restarts the workers when running. */
    void set_turbo(bool turbo);

    /* Caps how many workers TURBO may use (the thermal step between full speed
     * and COOL). Clamped to the worker limit; safe before start. */
    void set_thread_cap(int threads);

    /* Stop and join every worker. Idempotent. */
    void stop();

    void fill_counters(int64_t out[kCounterSlots]);

    /* Cumulative hashes per worker slot, for the htop-style worker panel.
     * Writes one slot per worker of the current mode and returns that count:
     * slots at or past it are written as zero, so a thread cap change never
     * leaves an old total behind. Counters accumulate across jobs within a
     * session and are cleared when a session starts. */
    int fill_thread_hashes(int64_t out[kMaxThreads]);

    /* The most recently hashed digest, internal order. False when none yet. */
    bool current_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]);

    /* The best digest of this job, internal order. False when none yet. */
    bool best_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]);

    /* Pops the oldest found hash and returns its nonce, or -1 when the queue is
     * empty. The digest is copied into `out`. */
    int64_t take_found_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]);

    const char *version() const { return version_.c_str(); }
    const char *backend_name() const { return backend_; }
    int max_threads() const { return max_threads_.load(std::memory_order_relaxed); }

private:
    using Clock = std::chrono::steady_clock;

    void spawn_workers_locked();
    void join_workers_locked();
    void restart_workers();
    void worker(int index);
    bool walk_nonces(const hashes_block_job &job, int index, uint64_t generation);

    void reset_job_counters();
    void flush_progress(uint64_t count, uint32_t last_nonce,
                        const uint8_t digest[HASHES_SHA256_DIGEST_SIZE], int worker_index);
    void record_found(uint32_t nonce, const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]);
    void consider_best(uint32_t nonce, const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]);
    void update_best_locked(const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]);
    double update_rate();
    int64_t now_ms() const;

    int effective_threads() const;
    bool turbo() const { return turbo_.load(std::memory_order_relaxed); }
    bool wants_continuous_hash() const {
        return turbo() || duty_hash_micros_.load(std::memory_order_relaxed) <= 0;
    }

    const hashes_compress_fn compress_;
    const char *backend_;
    std::string version_;
    std::atomic<bool> hardware_sha2_;

    std::atomic<int> max_threads_;
    std::atomic<int> thread_cap_;
    std::atomic<bool> turbo_;
    std::atomic<int> duty_hash_micros_;
    std::atomic<int> duty_sleep_micros_;

    /* Job: written under job_mutex_, copied by each worker on a new generation. */
    std::mutex job_mutex_;
    hashes_block_job job_;
    std::atomic<bool> job_valid_;
    std::atomic<uint64_t> job_generation_;

    /* Workers: running_ is what they poll; wake_mutex_ is theirs alone. */
    std::mutex workers_mutex_;
    std::vector<std::thread> workers_;
    std::atomic<bool> running_;
    std::atomic<State> state_;
    std::mutex wake_mutex_;
    std::condition_variable wake_cv_;

    std::atomic<uint64_t> hashes_;
    /* One cumulative counter per worker slot; written by its own thread only. */
    std::atomic<uint64_t> thread_hashes_[kMaxThreads];
    std::atomic<uint32_t> current_nonce_;
    std::atomic<int64_t> started_at_ms_;

    /* Last digest, for the live stream. A short lock per flush, not per hash. */
    std::mutex digest_mutex_;
    uint8_t current_digest_[HASHES_SHA256_DIGEST_SIZE];
    bool have_current_digest_;

    /* Best digest of the job, ranked by leading 64 bits before the lock. */
    std::mutex best_mutex_;
    std::atomic<uint64_t> best_top_;
    uint8_t best_digest_[HASHES_SHA256_DIGEST_SIZE];
    int best_nibbles_;

    std::mutex found_mutex_;
    FoundHash found_queue_[kFoundQueueSize];
    size_t found_head_;
    size_t found_count_;

    std::mutex rate_mutex_;
    Clock::time_point rate_at_;
    uint64_t rate_hashes_;
    double rate_;
};

}  // namespace hashes

#endif /* HASHES_MINER_H */
