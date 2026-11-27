# Native bridge contract (Kotlin side)

The app talks to the Phase 1 hash core through exactly one interface and one set
of JNI methods. This document is the contract the native side implements; if it
matches, the two halves plug together with no changes above the bridge.

## Library

- File: `libhashes.so`
- ABI: `arm64-v8a` only (the pure C fallback inside the native core is for
  emulators and native tests, not a second APK ABI)
- Location: built by CMake from `app/src/main/cpp` into the APK
  (`lib/arm64-v8a/libhashes.so`); no prebuilt file is committed
- Load: `System.loadLibrary("hashes")` from `NativeMiner`; a load failure is not
  fatal: `available` becomes false and the app runs the Kotlin fallback engine.

## Class and symbols

Kotlin object: `com.corejbh.hashes.engine.NativeMiner`. External methods are
declared on the object, so the C++ implementation is a JNI instance method of
that singleton class. The expected symbols:

```
Java_com_corejbh_hashes_engine_NativeMiner_nativeStart
Java_com_corejbh_hashes_engine_NativeMiner_nativeUpdateWork
Java_com_corejbh_hashes_engine_NativeMiner_nativeSetTurboMode
Java_com_corejbh_hashes_engine_NativeMiner_nativeSetThreadCap
Java_com_corejbh_hashes_engine_NativeMiner_nativeStop
Java_com_corejbh_hashes_engine_NativeMiner_nativePollCounters
Java_com_corejbh_hashes_engine_NativeMiner_nativePollThreadHashes
Java_com_corejbh_hashes_engine_NativeMiner_nativePollCurrentHash
Java_com_corejbh_hashes_engine_NativeMiner_nativePollBestHash
Java_com_corejbh_hashes_engine_NativeMiner_nativePollFoundHash
Java_com_corejbh_hashes_engine_NativeMiner_nativeVersion
```

Alternatively `RegisterNatives` in `JNI_OnLoad` may bind the same names; the
Kotlin declarations are the source of truth.

## Methods

### `nativeStart`

```c
jboolean nativeStart(JNIEnv*, jobject,
                     jbyteArray headerPrefix,   // 76 bytes
                     jbyteArray target,         // 32 bytes, big-endian
                     jlong nonceStart,          // 0 .. 2^32-1
                     jboolean turbo,
                     jint dutyHashMicros,       // COOL: 1500, TURBO: 0
                     jint dutySleepMicros);     // COOL: 31000, TURBO: 0
```

Starts worker threads on the job. The first 64 bytes of `headerPrefix` are the
midstate input and must be hashed once; the remaining 12 bytes are ntime and
nbits, and the 4-byte nonce is written by the core. Returns false only when the
core cannot start (for example a worker thread could not be created).

The core stops any previous run itself; Kotlin calls `start` after
`stop` when a session begins, and `updateWork` while a job changes.

### `nativeUpdateWork`

```c
jboolean nativeUpdateWork(JNIEnv*, jobject,
                          jbyteArray headerPrefix,
                          jbyteArray target,
                          jlong nonceStart);
```

Swaps the job without tearing down threads: recompute the midstate once, reset
the nonce counter, keep threads alive. Called for every `mining.notify`.

### `nativeSetTurboMode(jboolean turbo)`

Switches between COOL (one thread, duty cycle) and TURBO (multi-thread, ARMv8
SHA-256 path, worker priority `THREAD_PRIORITY_BACKGROUND`). Safe to call while
running, and safe to call before `start`.

### `nativeSetThreadCap(jint threads)`

Caps how many workers TURBO may use: the thermal step between full speed and
COOL. Clamped to 1..worker limit; the worker set restarts only when the number
actually changes. Safe before `start`. Called by the thermal guard on MODERATE,
and with 1 on the first SEVERE reading.

### `nativeStop()`

Stops the workers and joins them. Must be idempotent. Must not block longer than
a few hundred milliseconds; the UI calls it on the main thread's frame boundary.

## Snapshots

Kotlin polls about 30 times per second. Each poll must be a consistent read of an
`std::atomic` snapshot; no method may block on the workers.

### `nativePollCounters(jlongArray out)` - length 8

| Index | Meaning |
| --- | --- |
| 0 | state: 0 idle, 1 running, 2 stopping, 3 error |
| 1 | total hashes tried this job |
| 2 | current nonce (unsigned 32-bit) |
| 3 | worker thread count |
| 4 | hash rate in hashes/second; 0 means "Kotlin computes it from deltas" |
| 5 | best leading zero nibbles this job |
| 6 | flags, bit 0 = ARMv8 SHA-256 hardware path active |
| 7 | reserved, write 0 |

### `nativePollThreadHashes(jlongArray out)` - length 8, returns `jint`

Cumulative hashes per worker slot, one slot per worker of the current mode, and
the number of slots that carry a worker. Slots past that count must be written as
zero, so a lower thread cap never leaves an old total behind; the caller's array
is always eight long.

Kotlin differences two reads of this array to draw the terminal's per-worker bars
and never derives one worker's rate from another's, so the counter must be
per-thread and must count the same hashes the total does. Totals accumulate across
jobs within a session (a new job does not clear them) and start from zero when
`nativeStart` begins a session.

### `nativePollCurrentHash(jbyteArray out)` - 32 bytes

The most recently hashed digest, internal (little-endian) order. May be any
recent value; Kotlin reverses it for display.

### `nativePollBestHash(jbyteArray out)` - returns `jboolean`

Best digest this job, internal order. Return false and leave the array alone when
no hash has been recorded.

### `nativePollFoundHash(jbyteArray out)` - returns the nonce as `jlong`, or -1

A hash that met the target, drained by Kotlin so it can be submitted to the pool.
Fill `out` with the digest (internal order) and return the 32-bit nonce. The
queue is consumed: a second call without a new find returns -1. The queue should
hold at least a few entries and drop the oldest rather than block.

### `nativeVersion()` - returns a string

Human-readable core version, shown in the settings sheet. It is not parsed.

## Threading and ownership

- The core owns its threads. Kotlin never calls into the core per nonce; the only
  cross-JNI traffic is one job per block, one mode change, and about 30 reads per
  second.
- Buffers passed in are copied by the core or read under the JNI array lock
  before returning; Kotlin reuses its arrays between calls.
- `target` is 32 bytes big-endian. A hash meets it when the digest read
  little-endian is at or below the target.
- A nonce is 32-bit and wraps; reaching `0xFFFFFFFF` means the extranonce2 space
  is exhausted, which the UI reports as no share rather than a false positive.

## Duty cycle

COOL means one worker that hashes for `dutyHashMicros` and sleeps for
`dutySleepMicros` per 33 ms frame, keeping the CPU share under about 5% while the
terminal still refreshes at 30 fps. TURBO means continuous hashing on the worker
set. The Kotlin thermal guard is the only thing that changes either from a
reading: it calls `nativeSetThreadCap` on MODERATE and the first SEVERE reading,
and `nativeSetTurboMode(false)` after a second consecutive SEVERE one, so the
native side only has to honour both calls promptly.

## States the UI reads

`state` is the source of the `running` flag in `EngineSnapshot`. While a stop is
in progress the state is 2; the UI keeps showing the last counters until the
state becomes 0. An error state (3) is terminal for the job: Kotlin stops the
session and prints the reason from `nativeVersion` context.
