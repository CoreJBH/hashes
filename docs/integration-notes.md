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
- `./gradlew :app:testDebugUnitTest`: 173 tests, 0 failures.
- `./gradlew :app:lintDebug`: no errors (warnings are newer-dependency notices and
  a few typography hints).
- `./gradlew :app:assembleRelease -Phashes.unsignedRelease=true`: R8 minified
  release builds without a keystore; `com.corejbh.hashes.engine.NativeMiner`
  survives minification (the keep rule holds), and no dependency metadata is in
  the APK.
- Only `sha256_arm64_crypto.c.o` contains SHA-2 instructions; the other objects
  contain none, which is the property the SIGILL argument rests on.
- The C test sources compile and link for `arm64-v8a` with `-Werror`.

## The session, the service and the screen

The Phase 3 tree owned the session inside `MinerViewModel` and closed it on
`ON_STOP`. A session that survives the screen cannot live in a view model, so the
work moved down one layer:

- `session/MiningSession.kt` is the process-wide session: engine, `StratumClient`,
  `ThermalGuard`, the tracker and one one-second loop that refreshes the snapshot,
  reads the heat, drains found shares and submits them. Both the service and the
  view model reach the same instance through `MiningSession.get(context)`, and the
  session's state is a `StateFlow`, so a terminal opened on a running session shows
  it without asking the service for anything.
- `service/MinerForegroundService.kt` is a `dataSync` foreground service with a
  `STOP` action, a one-second notification refresh and the three exits the brief
  asks for: `ACTION_STOP`, `onTaskRemoved` and the button's `stopService` (which
  ends in `onDestroy`). `START_NOT_STICKY`, so no session is ever resurrected.
- `MinerViewModel` is now only the frame source: a 30 fps engine poll, the hash
  stream, the worker rates and the graphs, started on `ON_START` and stopped on
  `ON_STOP`. It never opens a socket and never stops the session.

`NativeMiner` gained one symbol, `nativePollThreadHashes`, so the `WORKERS` panel
draws each thread's own rate instead of dividing the total by the thread count.
It is the only contract change; `NativeBridgeContractTest` compares the
declarations with `jni_bridge.cpp` and caught nothing, and the C test checks that
the per-worker slots add up to the job total.

## The cross-review follow-ups: one reading, one layout, one update

The follow-up review of `9f8e235` raised two Medium findings and one Low; all
three are closed on `main`.

- **One reading at a time (`NativeMiningEngine`).** The screen polls the engine at
  30 fps on `Main` while the session's housekeeping loop polls it once a second on
  `Dispatchers.Default`. Both filled the same counter, worker, digest and
  found-share buffers, and the same rate smoother, so two overlapping polls could
  assemble a snapshot from two different readings, or a rate from a sample the
  caller never took. One `lock` now covers every method that touches those buffers
  or calls into the core: `start`, `updateWork`, `setTurboMode`, `setThreadCap`,
  `stop`, `close`, `snapshot` and `takeFoundShare`. The native calls never call
  back into Kotlin, so the sections cannot deadlock; the longest is `stop`, which
  waits for the core's own join. `NativeMiningEngineTest` gained two contention
  tests: a probe binding numbers every poll and counts how many calls are inside
  it at once; four threads take 400 snapshots each while a fifth replaces the job,
  every snapshot must carry a single generation, and no two calls may be inside
  the binding together. Both tests fail when the `snapshot` lock is replaced with
  a fresh object, and pass with it.
- **The controls can no longer be squeezed out (`MinerScreen`).** The layout used
  to decide `maxHeight >= 600dp` once and, on the "full" side, put the buttons in
  the same column as the weighted panels; a 600–700 dp screen at a 1.2x font
  scale made the fixed rows taller than the screen and left the buttons, the TURBO
  line and the pool button at 0 dp. The screen is now two parts: the controls are
  the last child of the outer column and are measured first at their natural
  height, and the body above them takes everything else through
  `weight(1f, fill = true)`. The body is a single scrolling column; the two fixed
  blocks report their laid-out height, and the stream and the log split what is
  left 70/30 with a floor (66 dp and 50 dp). A tall screen is therefore filled
  exactly, and a short screen, a split view or a large font scrolls instead of
  clipping. No screen height is compared against. `TerminalLayoutTest` covers the
  split, the exact fill and the floors.
- **No lost state (`MiningSession`).** `_state.value = transform(_state.value)` is
  a read-modify-write, and the session is written from `Main` (the buttons, the
  service intents) and from `Default` (work events, the thermal guard, the
  ticker); one of two simultaneous updates could disappear, which is how a share
  counter or the newest notice went missing. The single `update` helper now calls
  `MutableStateFlow.update`, and every transform is a pure function of the
  previous state, so a retry is safe.

Still open after that pass, and closed by the next one: the session's own
`sessionExtranonce1`, `pendingJob`, `extranonce2Counter` and `engineRunning` were
plain fields written from both dispatchers.

## The share target, and the session's job state

A cross-review bug that would have made the app useless on a real pool: the
engine was handed `job.networkBits`, the compact target a *block* has to beat.
The network difficulty is in the billions and a phone hashes at megahashes per
second, so the engine had nothing to report and the pool never saw a share - the
session ran, the counters moved, and nothing was ever submitted.

`Target.fromDifficulty` turns a pool difficulty into the target the engine mines
against - `DIFFICULTY_ONE / difficulty`, clamped to the 256-bit range - and a job
now goes out with that share target. The network target stands in only until the
pool announces a difficulty, because a share the pool would reject is worse than
no share. The panel keeps showing the network target and the network difficulty
of the job being hashed, since that is what the block race is about, and the
odds line still measures finding a block.

`mining.set_difficulty` often arrives on a job that already runs, so it is
applied through `engine.updateWork` at once instead of waiting for the next
`mining.notify`. The header and the extranonce2 stay those the engine is walking,
so a share found before or after the change goes out with the same job id; only
the bar moves. The engine's swap restarts its job counters and clears its
found-hash queue, so the session drains a queued share before it swaps.

The fields the earlier pass left open now live in `session/SessionJobs.kt`: one
lock covers the extranonce, the extranonce2 counter, the pool difficulty and the
work the engine holds, plus every engine call that reads them. `SessionJobsTest`
starts 100 jobs from four threads and gets 100 distinct extranonce2 values, shows
that a job cannot start once the session is closed, and that a difficulty with no
running job is remembered for the next one instead of being dropped.
`pendingJob` is `@Volatile`: written by the collector, cleared by a start on
another thread.

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
- **Background behaviour is not exercised here.** The foreground service, its
  notification refresh, the `POST_NOTIFICATIONS` request, `onTaskRemoved` and the
  Android 15+ `dataSync` timeout compile and pass lint, but no device has been left
  on the home screen with a session running; that is the first thing to try on the
  next real-device pass.
- **The layout is not rendered here.** The height split is unit tested
  (`TerminalLayoutTest`), but no device or preview has shown the pinned controls
  at a 1.2x font scale on a 600 dp screen; that is a check for the next
  real-device pass.

## Things left for Phase 4

- A signed `arm64-v8a` release, the GitHub Release and the site entry.
- A real-pool session and a real-device thermal run.
- The reproducible-build check: build twice with `-Phashes.unsignedRelease=true`
  and compare the unsigned APKs.
