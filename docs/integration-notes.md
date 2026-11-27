# Integration notes

How the three phases became one tree, what the merge had to decide, and what is
still unverified. `docs/native-bridge-contract.md` is the bridge itself; this file
is about everything around it.

## What came from where

| Layer | Source | Notes |
| --- | --- | --- |
| `app/src/main/cpp/`, `CMakeLists.txt` | Phase 1 | Untouched: SHA-256 (portable + ARMv8), dispatch, header hashing, the engine, the JNI bridge |
| `app/build.gradle.kts` | Phase 1 | Kept whole - pinned `ndkVersion` 30.0.16248370, CMake 3.22.1, `dependenciesInfo` off, fail-closed release signing. Phase 2/3 only added dependencies (CameraX, ZXing, view model, `org.json` for tests) |
| `app/src/main/java/.../engine/NativeMiner.kt` | Phase 1 | The declarations the native symbols match: `nativePollBestHash` returns `Boolean`, `nativePollFoundHash` returns `Long`, `nativeSetThreadCap` takes an `Int` |
| `app/src/main/java/.../thermal/` | Phase 1 + Phase 3 | `ThermalPolicy` and `ThermalMonitor` from Phase 1; `ThermalGuard` is new and replaces Phase 1's `ThermalController` |
| `crypto/`, `network/`, `data/`, `ui/`, `MainActivity.kt`, `res/`, `fastlane/`, `tools/` | Phase 2/3 | Copied as delivered |
| `scripts/verify-english.py` | Phase 1 script, Phase 3 rule | Now checks the brand rule as well as English-only |

Phase 1's placeholder Kotlin was removed rather than kept beside the real app: the
`SelfTestJob` screen, its `MinerSession`, its `EngineCounters`/`Hex`/`FoundShare`/
`MiningJob` models and their four test files. Every one of them is superseded by
`engine/`, `crypto/` and `network/` equivalents, and two parallel models of the
same job would be one too many. The counter layout they documented is still
written down in `NativeMinerBinding.Counter`, and the native declarations are
still checked against the C++ exports by `NativeBridgeContractTest`.

## The engine seam

`JniMinerBinding` adapts Phase 1's `NativeMiner` declarations to the
`NativeMinerBinding` interface Phase 2/3 wrote against, so the network and UI code
never sees JNI. Three points had to be reconciled:

1. `nativePollBestHash` returns `Boolean` (Phase 1), not `Int` (the staging tree
   declared `Int`); the binding returns it directly.
2. `nativeSetThreadCap` exists in Phase 1's declarations and in the C++
   (`miner.cpp`), and was missing from the staging binding; the interface now has
   `setThreadCap(threads)` and the binding calls it.
3. `MiningEngine` extends a one-method-plus-cap `EnginePowerControl`, so the
   thermal guard can hold the engine without depending on the whole engine
   contract. The Kotlin fallback leaves `setThreadCap` at its no-op default.

## The two thermal controls, now one

Phase 1 had a two-step policy (MODERATE halves the threads, SEVERE returns to
COOL) and Phase 3 had its own counter (two consecutive severe readings, then
`THERMAL GUARD: TURBO DISABLED`). Both now live in `ThermalGuard`:

- MODERATE (or 40.0 C): `nativeSetThreadCap(maxThreads / 2)`, TURBO keeps running.
- First SEVERE (or 43.0 C): `nativeSetThreadCap(1)`.
- Second consecutive SEVERE: `nativeSetTurboMode(false)`, the TURBO setting is
  persisted as off, and the terminal shows `THERMAL GUARD` with
  `[ RESET THERMAL GUARD ]`. TURBO is refused until that reset.
- A clean reading clears the strikes and restores the full worker set.

`ThermalMonitor` still reads the OS: API 29+ `PowerManager` thermal status,
API 26-28 battery temperature from the sticky broadcast, both through
`ThermalPolicy`, so a decision is one shape whatever the source. The old
per-frame `monitorThermal()` in the view model is gone; the monitor is started
with the session and stopped with it.

## Facts checked for the pool presets

Verified against the pools' own pages and measurements in November 2026:

- `public-pool.io` - solo Stratum V1 on `3333`, Stratum V1 over TLS on `4333`,
  0% fee, username is the Bitcoin address plus a worker tag, password `x`.
  The pool assigns difficulty; the app sends `mining.suggest_difficulty 1.0` as a
  hint, which the pool may ignore.
- `pool.nerdminers.org:3333` - ultra-low difficulty, TCP only, no TLS listener;
  the app therefore shows `STRATUM+TCP` and cannot encrypt that line.
- `solo.ckpool.org:3333` - TCP, 2% fee, minimum share difficulty 10,000; kept as
  the "hardcore" option and labelled as not for small hash rates.

The custom field accepts `stratum+ssl://host:port`, `stratum+tls://host:port` and
`stratum+tcp://host:port`; a bare `host:port` is treated as TLS.

## What was verified in the merge environment

- `./gradlew :app:assembleDebug` with NDK 30.0.16248370 and CMake 3.22.1: builds,
  and the APK carries `lib/arm64-v8a/libhashes.so` with the ten JNI symbols.
- `./gradlew :app:testDebugUnitTest`: 149 tests, 0 failures.
- `./gradlew :app:lintDebug`: no errors (warnings are newer-dependency notices and
  a few typography hints).
- `./gradlew :app:assembleRelease -Phashes.unsignedRelease=true`: R8 minified
  release builds without a keystore; `com.corejbh.hashes.engine.NativeMiner`
  survives minification (the keep rule holds), and no dependency metadata is in
  the APK.
- Only `sha256_arm64_crypto.c.o` contains SHA-2 instructions; the other objects
  contain none, which is the property the SIGILL argument rests on.
- The C test sources compile and link for `arm64-v8a` with `-Werror`.

## What has not been verified

- **The native tests did not execute.** This environment has no host C library,
  no qemu and no arm64 hardware: the NDK's clang targets Android only, so the
  host `scripts/run-native-tests.sh` run and the qemu `arm64` run of the same
  vectors have to happen on a machine with a host compiler and qemu. The C core
  compiles clean under `-Werror` for arm64 and the library links, but no C test
  assertion has been observed to pass here. The Kotlin SHA-256/midstate path is
  covered by the JVM suite against the same real headers.
- No socket has been opened against a real pool from this environment. The
  transport, handshake and message handling are covered by unit tests with real
  captured payloads and a real header vector, but not by a live session.
- The TLS path relies on the platform trust store and host name verification
  (`endpointIdentificationAlgorithm = "HTTPS"`); it has not been exercised
  against `public-pool.io`'s certificate from here.
- The QR scanner needs a device with a camera; only the decoder and the YUV
  conversion are unit tested.
- No real-device session: hash rate, temperature behaviour and share acceptance
  on a low-difficulty pool are unmeasured.

## Things left for Phase 4

- A signed `arm64-v8a` release, the GitHub Release and the site entry.
- A real-pool session and a real-device thermal run.
- The reproducible-build check: build twice with `-Phashes.unsignedRelease=true`
  and compare the unsigned APKs.
