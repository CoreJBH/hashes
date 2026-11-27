#include "miner.h"

#include <algorithm>
#include <cstring>
#include <string>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace hashes {
namespace {

using Clock = std::chrono::steady_clock;

/* How often a worker looks at the stop flag, the job generation and the duty
 * clock. Large enough that the checks cost nothing, small enough that a stop or
 * a new job lands in about a millisecond. */
constexpr uint64_t kCheckInterval = 2048;

/* THREAD_PRIORITY_BACKGROUND on Android is nice value 10: TURBO must not push
 * the UI thread out of its frame. */
void lower_thread_priority() {
#if defined(__ANDROID__)
    constexpr int kBackgroundNice = 10;
    setpriority(PRIO_PROCESS, 0, kBackgroundNice);
#endif
}

/** The leading 64 bits of a block hash: the digest read from its end. */
uint64_t leading_bits(const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    uint64_t top = 0;
    for (int i = 0; i < 8; ++i) {
        top = (top << 8) | digest[HASHES_SHA256_DIGEST_SIZE - 1 - i];
    }
    return top;
}

/** Leading zero nibbles of a block hash, the way a terminal shows it. */
int leading_zero_nibbles(const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    int nibbles = 0;
    for (int i = HASHES_SHA256_DIGEST_SIZE - 1; i >= 0; --i) {
        uint8_t byte = digest[i];
        if (byte == 0) {
            nibbles += 2;
            continue;
        }
        if (byte < 0x10) {
            ++nibbles;
        }
        break;
    }
    return nibbles;
}

}  // namespace

NativeMiner::NativeMiner(int worker_limit)
    : compress_(hashes_sha256_selected_compress()),
      backend_(hashes_sha256_backend_name()),
      hardware_sha2_(std::strcmp(backend_, "armv8-sha2") == 0),
      max_threads_(1),
      thread_cap_(1),
      turbo_(false),
      duty_hash_micros_(0),
      duty_sleep_micros_(0),
      job_valid_(false),
      job_generation_(0),
      running_(false),
      state_(State::Idle),
      hashes_(0),
      current_nonce_(0),
      started_at_ms_(0),
      have_current_digest_(false),
      best_top_(UINT64_MAX),
      best_nibbles_(0),
      found_head_(0),
      found_count_(0),
      rate_at_(Clock::now()),
      rate_hashes_(0),
      rate_(0.0) {
    std::memset(&job_, 0, sizeof(job_));
    std::memset(current_digest_, 0, sizeof(current_digest_));
    std::memset(best_digest_, 0, sizeof(best_digest_));
    std::memset(found_queue_, 0, sizeof(found_queue_));

    /* Leave one core for the system and the UI, and never more than eight: past
     * that a phone's thermal budget gives the extra threads nothing to spend. */
    int limit;
    if (worker_limit > 0) {
        limit = std::min(worker_limit, 8);
    } else {
        unsigned cores = std::thread::hardware_concurrency();
        int usable = cores > 1 ? static_cast<int>(cores) - 1 : 1;
        limit = std::min(usable, 8);
    }
    max_threads_.store(limit, std::memory_order_relaxed);
    thread_cap_.store(limit, std::memory_order_relaxed);

    version_ = std::string("0.1.0 phase-1 ") + backend_;
}

NativeMiner::~NativeMiner() { stop(); }

int NativeMiner::effective_threads() const {
    if (!turbo()) {
        return 1;
    }
    int cap = thread_cap_.load(std::memory_order_relaxed);
    int limit = max_threads_.load(std::memory_order_relaxed);
    return std::max(1, std::min(cap, limit));
}

int64_t NativeMiner::now_ms() const {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               Clock::now().time_since_epoch())
        .count();
}

void NativeMiner::reset_job_counters() {
    hashes_.store(0, std::memory_order_relaxed);
    current_nonce_.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(digest_mutex_);
        have_current_digest_ = false;
        std::memset(current_digest_, 0, sizeof(current_digest_));
    }
    {
        std::lock_guard<std::mutex> lock(best_mutex_);
        best_top_.store(UINT64_MAX, std::memory_order_relaxed);
        best_nibbles_ = 0;
        std::memset(best_digest_, 0, sizeof(best_digest_));
    }
    {
        std::lock_guard<std::mutex> lock(found_mutex_);
        found_head_ = 0;
        found_count_ = 0;
        std::memset(found_queue_, 0, sizeof(found_queue_));
    }
    {
        std::lock_guard<std::mutex> lock(rate_mutex_);
        rate_at_ = Clock::now();
        rate_hashes_ = 0;
        rate_ = 0.0;
    }
    started_at_ms_.store(now_ms(), std::memory_order_relaxed);
}

bool NativeMiner::start(const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
                        const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start,
                        bool turbo, int duty_hash_micros, int duty_sleep_micros) {
    stop();

    turbo_.store(turbo, std::memory_order_relaxed);
    duty_hash_micros_.store(std::max(0, duty_hash_micros), std::memory_order_relaxed);
    duty_sleep_micros_.store(std::max(0, duty_sleep_micros), std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(job_mutex_);
        hashes_block_job_init(&job_, header_prefix, target, nonce_start, compress_);
        job_valid_.store(true, std::memory_order_release);
        job_generation_.fetch_add(1, std::memory_order_acq_rel);
    }
    reset_job_counters();

    std::lock_guard<std::mutex> lock(workers_mutex_);
    running_.store(true, std::memory_order_release);
    state_.store(State::Running, std::memory_order_release);
    try {
        spawn_workers_locked();
    } catch (...) {
        running_.store(false, std::memory_order_release);
        workers_.clear();
        state_.store(State::Error, std::memory_order_release);
        return false;
    }
    return true;
}

bool NativeMiner::update_work(const uint8_t header_prefix[HASHES_HEADER_PREFIX_SIZE],
                              const uint8_t target[HASHES_TARGET_SIZE], uint32_t nonce_start) {
    {
        std::lock_guard<std::mutex> lock(job_mutex_);
        hashes_block_job_init(&job_, header_prefix, target, nonce_start, compress_);
        job_valid_.store(true, std::memory_order_release);
        job_generation_.fetch_add(1, std::memory_order_acq_rel);
    }
    reset_job_counters();
    wake_cv_.notify_all();
    return true;
}

void NativeMiner::set_thread_cap(int threads) {
    int limit = max_threads_.load(std::memory_order_relaxed);
    int wanted = std::max(1, std::min(threads, limit));
    if (thread_cap_.exchange(wanted, std::memory_order_acq_rel) == wanted) {
        return;
    }
    if (running_.load(std::memory_order_acquire) && turbo()) {
        restart_workers();
    }
}

void NativeMiner::set_turbo(bool turbo) {
    if (turbo_.exchange(turbo, std::memory_order_acq_rel) == turbo) {
        return;
    }
    restart_workers();
}

void NativeMiner::spawn_workers_locked() {
    int count = effective_threads();
    workers_.reserve(static_cast<size_t>(count));
    for (int index = 0; index < count; ++index) {
        workers_.emplace_back(&NativeMiner::worker, this, index);
    }
}

void NativeMiner::join_workers_locked() {
    for (std::thread &worker_thread : workers_) {
        if (worker_thread.joinable()) {
            worker_thread.join();
        }
    }
    workers_.clear();
}

void NativeMiner::restart_workers() {
    std::lock_guard<std::mutex> lock(workers_mutex_);
    if (!running_.load(std::memory_order_acquire)) {
        return;
    }
    state_.store(State::Stopping, std::memory_order_release);
    running_.store(false, std::memory_order_release);
    wake_cv_.notify_all();
    join_workers_locked();
    if (!job_valid_.load(std::memory_order_acquire)) {
        state_.store(State::Idle, std::memory_order_release);
        return;
    }
    running_.store(true, std::memory_order_release);
    state_.store(State::Running, std::memory_order_release);
    spawn_workers_locked();
}

void NativeMiner::stop() {
    std::lock_guard<std::mutex> lock(workers_mutex_);
    if (state_.load(std::memory_order_acquire) == State::Error) {
        state_.store(State::Idle, std::memory_order_release);
        return;
    }
    if (!running_.exchange(false, std::memory_order_acq_rel)) {
        state_.store(State::Idle, std::memory_order_release);
        return;
    }
    state_.store(State::Stopping, std::memory_order_release);
    wake_cv_.notify_all();
    join_workers_locked();
    state_.store(State::Idle, std::memory_order_release);
}

void NativeMiner::worker(int index) {
    if (turbo()) {
        lower_thread_priority();
    }

    uint64_t generation = 0;
    hashes_block_job job;
    bool have_job = false;
    std::memset(&job, 0, sizeof(job));

    while (running_.load(std::memory_order_acquire)) {
        uint64_t current = job_generation_.load(std::memory_order_acquire);
        if (!have_job || current != generation) {
            std::lock_guard<std::mutex> lock(job_mutex_);
            job = job_;
            have_job = job_valid_.load(std::memory_order_relaxed);
            generation = job_generation_.load(std::memory_order_relaxed);
        }

        bool finished = !have_job;
        if (have_job) {
            finished = walk_nonces(job, index, generation);
        }

        if (finished) {
            /* Nothing left to hash in this job: wait for the next one instead of
             * spinning on an exhausted range. */
            std::unique_lock<std::mutex> lock(wake_mutex_);
            wake_cv_.wait_for(lock, std::chrono::milliseconds(50), [this, generation] {
                return !running_.load(std::memory_order_acquire) ||
                       job_generation_.load(std::memory_order_acquire) != generation;
            });
        }
    }
}

bool NativeMiner::walk_nonces(const hashes_block_job &job, int index, uint64_t generation) {
    const int threads = effective_threads();
    const bool continuous = wants_continuous_hash();
    const int64_t duty_hash = duty_hash_micros_.load(std::memory_order_relaxed);
    const int64_t duty_sleep = duty_sleep_micros_.load(std::memory_order_relaxed);

    /* A nonce is 32 bits and the walk never wraps: 0xFFFFFFFF is the end of the
     * space, and a range that reaches it is finished rather than repeated. */
    const uint64_t span = (1ULL << 32) - static_cast<uint64_t>(job.nonce_start);

    uint64_t offset = static_cast<uint64_t>(index);
    uint64_t pending = 0;
    Clock::time_point window_start = Clock::now();

    while (offset < span) {
        uint32_t nonce = job.nonce_start + static_cast<uint32_t>(offset);
        uint8_t digest[HASHES_SHA256_DIGEST_SIZE];
        hashes_block_header_hash(&job, nonce, compress_, digest);
        ++pending;
        offset += static_cast<uint64_t>(threads);

        if (hashes_hash_meets_target(digest, job.target)) {
            record_found(nonce, digest);
        } else {
            consider_best(nonce, digest);
        }

        if (pending >= kCheckInterval) {
            flush_progress(pending, nonce, digest);
            pending = 0;

            if (!running_.load(std::memory_order_acquire) ||
                job_generation_.load(std::memory_order_acquire) != generation) {
                return false;
            }

            if (!continuous) {
                int64_t spent = std::chrono::duration_cast<std::chrono::microseconds>(
                                    Clock::now() - window_start)
                                    .count();
                if (spent >= duty_hash) {
                    if (duty_sleep > 0) {
                        std::this_thread::sleep_for(std::chrono::microseconds(duty_sleep));
                    }
                    window_start = Clock::now();
                }
            }
        }
    }

    flush_progress(pending, job.nonce_start + static_cast<uint32_t>(span - 1), nullptr);
    return true;
}

void NativeMiner::flush_progress(uint64_t count, uint32_t last_nonce,
                                 const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    if (count > 0) {
        hashes_.fetch_add(count, std::memory_order_relaxed);
    }
    current_nonce_.store(last_nonce, std::memory_order_relaxed);
    if (digest != nullptr) {
        std::lock_guard<std::mutex> lock(digest_mutex_);
        std::memcpy(current_digest_, digest, HASHES_SHA256_DIGEST_SIZE);
        have_current_digest_ = true;
    }
}

void NativeMiner::consider_best(uint32_t nonce, const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    /* The leading 64 bits decide almost every comparison, so a hash that cannot
     * be the best of the job never takes the lock. */
    if (leading_bits(digest) > best_top_.load(std::memory_order_relaxed)) {
        return;
    }
    update_best_locked(digest);
    (void)nonce;
}

void NativeMiner::record_found(uint32_t nonce, const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    {
        std::lock_guard<std::mutex> lock(found_mutex_);
        FoundHash &slot = found_queue_[found_head_];
        slot.nonce = nonce;
        std::memcpy(slot.digest, digest, HASHES_SHA256_DIGEST_SIZE);
        found_head_ = (found_head_ + 1) % kFoundQueueSize;
        if (found_count_ < kFoundQueueSize) {
            ++found_count_;
        }
    }
    update_best_locked(digest);
}

void NativeMiner::update_best_locked(const uint8_t digest[HASHES_SHA256_DIGEST_SIZE]) {
    std::lock_guard<std::mutex> lock(best_mutex_);
    uint64_t top = leading_bits(digest);
    if (top >= best_top_.load(std::memory_order_relaxed)) {
        return;
    }
    best_top_.store(top, std::memory_order_relaxed);
    best_nibbles_ = leading_zero_nibbles(digest);
    std::memcpy(best_digest_, digest, HASHES_SHA256_DIGEST_SIZE);
}

void NativeMiner::fill_counters(int64_t out[kCounterSlots]) {
    State state = state_.load(std::memory_order_acquire);
    out[0] = static_cast<int64_t>(state);
    out[1] = static_cast<int64_t>(hashes_.load(std::memory_order_relaxed));
    out[2] = static_cast<int64_t>(current_nonce_.load(std::memory_order_relaxed));
    out[3] = static_cast<int64_t>(state == State::Running ? effective_threads() : 0);
    out[4] = static_cast<int64_t>(update_rate());
    {
        std::lock_guard<std::mutex> lock(best_mutex_);
        out[5] = best_nibbles_;
    }
    out[6] = hardware_sha2_.load(std::memory_order_relaxed) ? 1 : 0;
    out[7] = 0;
}

double NativeMiner::update_rate() {
    std::lock_guard<std::mutex> lock(rate_mutex_);
    Clock::time_point now = Clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(now - rate_at_).count();
    uint64_t total = hashes_.load(std::memory_order_relaxed);
    /* A window of at least a quarter second, refreshed as the UI asks: short
     * enough to follow the duty cycle, long enough not to jitter. */
    if (elapsed >= 250000) {
        rate_ = static_cast<double>(total - rate_hashes_) * 1000000.0 /
                static_cast<double>(elapsed);
        rate_hashes_ = total;
        rate_at_ = now;
    }
    return rate_;
}

bool NativeMiner::current_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]) {
    std::lock_guard<std::mutex> lock(digest_mutex_);
    std::memcpy(out, current_digest_, HASHES_SHA256_DIGEST_SIZE);
    return have_current_digest_;
}

bool NativeMiner::best_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]) {
    std::lock_guard<std::mutex> lock(best_mutex_);
    if (best_top_.load(std::memory_order_relaxed) == UINT64_MAX) {
        return false;
    }
    std::memcpy(out, best_digest_, HASHES_SHA256_DIGEST_SIZE);
    return true;
}

int64_t NativeMiner::take_found_hash(uint8_t out[HASHES_SHA256_DIGEST_SIZE]) {
    std::lock_guard<std::mutex> lock(found_mutex_);
    if (found_count_ == 0) {
        return -1;
    }
    size_t oldest = (found_head_ + kFoundQueueSize - found_count_) % kFoundQueueSize;
    FoundHash entry = found_queue_[oldest];
    --found_count_;
    std::memcpy(out, entry.digest, HASHES_SHA256_DIGEST_SIZE);
    return static_cast<int64_t>(entry.nonce);
}

}  // namespace hashes
