# Hashes

A bitcoin block header hasher for Android. It carries no wallet keys and no
seed: a job is an 80-byte block header and a target, and the app reports the
hashes it finds against that target. **Phase 1 is the engine only** - no pool,
no network, no address entry, and nothing is submitted anywhere. The network and
UI phases plug in above the bridge and do not change it.

Package `com.corejbh.hashes`, `minSdk` 26, `targetSdk` 36, `arm64-v8a` only.

## What is here

| Layer | Files | What it does |
| --- | --- | --- |
| SHA-256 | `app/src/main/cpp/sha256_portable.c` | Reference compression, every CPU |
| SHA-256 (ARMv8) | `app/src/main/cpp/sha256_arm64_crypto.c` | SHA256H/H2/SU0/SU1 intrinsics; this file only, compiled with `-march=armv8-a+crypto` |
| Dispatch | `app/src/main/cpp/sha256_dispatch.c` | `getauxval(AT_HWCAP) & HWCAP_SHA2` once, then a function pointer |
| Header hashing | `app/src/main/cpp/block_header.h` | Midstate over header bytes 0..63, then the 12 fixed tail bytes, the 4-byte nonce, padding and a second SHA-256 |
| Engine | `app/src/main/cpp/miner.cpp` | `std::thread` workers, `std::atomic` counters, one snapshot, a found-hash queue |
| JNI | `app/src/main/cpp/jni_bridge.cpp` | The nine bridge methods, exported under the names the Kotlin object resolves |
| Bridge | `app/src/main/java/com/corejbh/hashes/engine/NativeMiner.kt` | The Kotlin declarations, one per native method |
| App shell | `app/src/main/java/com/corejbh/hashes/` | A placeholder screen, a session, the jobs and the found-hash model |
| Thermal | `app/src/main/java/com/corejbh/hashes/thermal/` | API 29+ thermal status, API 26-28 battery temperature, two-stage slow-down |

### Two things the build is careful about

**A SIGILL is impossible by construction.** `-march=armv8-a+crypto` is applied to
`sha256_arm64_crypto.c` alone, as a per-source compile option in
`CMakeLists.txt`. Every other translation unit is built for plain armv8-a, and
the accelerated function is reachable only through the pointer that
`sha256_dispatch.c` binds after asking the kernel whether the CPU has the SHA2
extension. The built library carries `sha256h`/`sha256h2`/`sha256su` instructions
and the portable code as well.

**The engine never runs a JNI call per hash.** Kotlin hands over a job, then reads
counters about thirty times a second. The workers update atomics; the UI reads
them; the only lock in the poll path is over the best digest.

## Bridge

`docs/native-bridge-contract.md` is the contract; `NativeMiner` implements it.

| Method | Purpose |
| --- | --- |
| `nativeStart(headerPrefix, target, nonceStart, turbo, dutyHashMicros, dutySleepMicros)` | Start a job; returns false when the workers cannot be created |
| `nativeUpdateWork(headerPrefix, target, nonceStart)` | Swap the job under the running workers |
| `nativeSetTurboMode(turbo)` | COOL (one thread, duty cycle) or TURBO (every worker, background priority) |
| `nativeStop()` | Stop and join; idempotent |
| `nativePollCounters(out[8])` | state, hashes, nonce, threads, hash rate, best zero nibbles, flags, reserved |
| `nativePollCurrentHash(out)` | The most recent digest, internal order |
| `nativePollBestHash(out)` | Best digest of the job, or false when there is none |
| `nativePollFoundHash(out)` | Nonce of the oldest found hash, or -1; the queue drops the oldest when full |
| `nativeVersion()` | Human-readable core version |

One method sits outside the contract: `nativeSetThreadCap(threads)` caps how many
workers TURBO may use. It exists because the finer thermal step (MODERATE halves
the threads) has no other way to reach the core; a binding that does not declare
it simply keeps the full worker set, and the coarse guard - severe reading turns
TURBO off - still works.

A nonce is 32 bits and the walk never wraps: reaching `0xFFFFFFFF` finishes the
job rather than repeating the space.

### COOL and TURBO

- `COOL` (default): one worker, 1.5 ms of hashing in every ~32.5 ms window - a
  30 fps duty cycle that keeps the CPU share low and the frame pacing natural.
- `TURBO`: every worker thread (cores minus one, at most eight) at background
  priority, so a busy UI thread still gets its frames.
- `ThermalPolicy`: `MODERATE` (or 40.0 C battery) halves TURBO's threads;
  `SEVERE` (or 43.0 C) returns the engine to COOL, and TURBO is refused while the
  reading stays severe.

## Build

The NDK and CMake versions are pinned: `ndkVersion = "30.0.16248370"`, CMake
`3.22.1`.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
python3 scripts/verify-english.py
```

Release builds are signed or they do not happen. The key comes from
`keystore/keystore.properties` (never committed) or from `HASHES_KEYSTORE_FILE`,
`HASHES_KEYSTORE_PASSWORD`, `HASHES_KEY_ALIAS` and `HASHES_KEY_PASSWORD`; the
debug key is never a fallback. A builder that signs the artifact with its own
key - F-Droid does - asks for an unsigned release explicitly:

```sh
./gradlew assembleRelease -Phashes.unsignedRelease=true
```

Reproducibility: `dependenciesInfo` is false for APK and bundle, the NDK and
CMake versions are fixed, and CMake passes `-ffile-prefix-map` for the source and
build directories so no build-host path reaches an object file.

## Tests

```sh
# Host: SHA-256 vectors, real block headers, the midstate path, and the engine's
# own behaviour (threads, counters, queue, mode, stop).
CC=cc CXX=c++ ./scripts/run-native-tests.sh

# The same hash tests for arm64 with the SHA2 extension, under qemu: this is how
# the accelerated path is checked without a phone, comparing the intrinsic
# compression against the portable one on every block.
CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
QEMU_AARCH64=qemu-aarch64-static \
./scripts/run-native-tests-aarch64.sh
```

The C vectors are the genesis block (#0), block 1 and block 170000: their double
SHA-256 is public, and each test also checks that the mined header meets its own
target and produces the same hash through the midstate path as through a full
double hash. SHA-256 itself is checked against FIPS 180-4 vectors ("abc", empty,
a 56-byte two-block message, one million `a`).

Kotlin unit tests cover the counters layout the bridge fills, the hex conversion
used to display block hashes, job validation, share difficulty, and the thermal
policy table.

## Not in Phase 1

Stratum V1, pool presets, address validation, the terminal UI, the live hash
stream and the all-time best. The engine is shaped for them: a job carries a
nonce start so a pool can hand out slices, found hashes come out in order, and
the state machine reports stopping and error so the UI can say what happened.
