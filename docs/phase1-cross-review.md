# Phase 1 cross-review (C/NDK hash core)

The Phase 1 core on `main` at `b80af40` was read line by line and checked against
its own documentation, the FIPS 180-4 and ARMv8 references, and the bridge
contract. What follows is what held, what was fixed during the merge, and what is
still open.

## What checked out

| Area | Verdict |
| --- | --- |
| Portable compression | FIPS 180-4 structure and the 64 constants are correct, padding and the 64-bit length are handled for both the one-block and two-block tail cases |
| Header hashing | Midstate over bytes 0..63, tail of 12 bytes, nonce little-endian at 12..15, padding at 16 with `0x0280` (640-bit) length, second block with `0x0100` (256-bit) — the standard layout, and the same numbers a real header needs |
| ARMv8 crypto path | `SHA256H`/`H2`/`SU0`/`SU1` are driven in the standard four-round grouping with the round constants added to the message words; the sequence is the one BoringSSL uses, and round constant offsets step 4 at a time as they must |
| SIGILL avoidance | `-march=armv8-a+crypto` is a per-source property of `sha256_arm64_crypto.c` only; the binary confirms it: only `sha256_arm64_crypto.c.o` contains `sha256h`/`sha256h2`/`sha256su` (56 instructions), every other object contains none |
| Dispatch | One `getauxval(AT_HWCAP) & HWCAP_SHA2` check cached in a C11 atomic function pointer; header fallback for libcs without `asm/hwcap.h` |
| Engine | `std::atomic` counters, a snapshot that never blocks the workers, found-hash ring that drops the oldest, best digest compared on the leading 64 bits before taking a lock, background thread priority in TURBO, nonce walk that stops at `0xFFFFFFFF` instead of wrapping |
| JNI surface | Ten exported symbols, named exactly as `NativeMiner` resolves them; arrays bounds-checked; nonce validated to 0..2³²−1; `nativePollFoundHash` returns `jlong` so `-1` cannot collide with a nonce at `0xFFFFFFFF` |
| Build hygiene | `-O3 -fvisibility=hidden -fstack-protector-strong -Wall -Wextra -Werror`, `-ffile-prefix-map` for reproducible objects, `--enable-v1Signing=false`/v2 only, fail-closed release signing, `dependenciesInfo` off |

The one gap in the Phase 1 documentation was a count: it said "nine methods",
while the contract plus `nativeSetThreadCap` is ten symbols. The contract now
lists all ten.

## Fixed during the merge

Both are in the C core and both are small; neither could be executed here (see
below), so they are flagged as verified by inspection only, to be re-checked by
the native test run on a machine that can run it.

1. **The target comparison rejected an exact match.** `hashes_hash_meets_target`
   returned "below" only when a differing byte was smaller, so a hash exactly
   equal to the target was a miss. Bitcoin's own check is `cmp <= 0`, and the
   contract in `docs/native-bridge-contract.md` says "at or below", and the Kotlin
   fallback (`FallbackHashEngine.meetsTarget`) already treated equality as a meet.
   The C now returns 1 when all 32 bytes are equal. (Probability per hash:
   2⁻²⁵⁶. It is a specification mismatch, not a mining problem.)
2. **A failed worker spawn could terminate the process.** `start()` cleared the
   `std::vector<std::thread>` after a `std::system_error` from `emplace_back`,
   which destroys joinable threads and calls `std::terminate` instead of returning
   `false` as the contract promises. The catch block now drops `running_`, wakes
   the condition variable and joins what was created.

## Open, not changed

- Up to 2047 counted hashes are dropped when a job is replaced or a session stops,
  because the counter is flushed every 2048 hashes. Cosmetic for a hashrate line,
  and fixing it would mean a flush on every job switch; recorded rather than
  changed.
- `restart_workers()` (reached from `nativeSetTurboMode` / `nativeSetThreadCap`)
  can still propagate a thread-creation failure out through JNI. It is the same
  class of problem as the fixed one, on a path that only runs on a mode change;
  the fix would be to catch there too and leave the previous mode in place.
- The README's "the only lock in the poll path is over the best digest" is
  slightly generous: `fill_counters` also takes the rate mutex and the digest
  mutex. No correctness impact; the locks are short.

## Not verified here

There was no system C compiler, no qemu and no arm64 hardware in the environment
this review ran in, so no C assertion was observed to pass at review time. Since
then the host suite has been built and run, using a cross-compiler as the host C
compiler for the portable path (`zig cc -target x86_64-linux-gnu`):
`hashes-native-test` passes 21 checks and `hashes-miner-test` 23, with no
failures. The target-equality case added with them fails as it should when the
comparison is put back to a strict `<`. What was done at review time instead:

- the whole native core and both native test programs compile for `arm64-v8a`
  with `-Wall -Wextra -Werror` against NDK 30.0.16248370 and link statically;
- the APK build compiles the core through CMake and the resulting
  `libhashes.so` carries all ten JNI symbols;
- the SHA-2 instruction placement in the objects is as the design claims;
- the Kotlin half of the same algorithm (SHA-256, midstate, header assembly,
  target arithmetic) is covered by the JVM suite against real mainnet headers.

`scripts/run-native-tests.sh` (host) and `scripts/run-native-tests-aarch64.sh`
(qemu) remain the way to execute the C vectors; the host run above was the first
of the two, so the ARMv8 half still has to run before the Phase 4 release.
