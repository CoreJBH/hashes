#!/bin/sh
# Build and run the native tests for the hash engine on the host.
#
#   ./scripts/run-native-tests.sh
#
# CC and CXX select the compilers (default: cc and c++). The host build uses the portable
# compression only: the ARMv8 path cannot be executed on an x86_64 host, and
# scripts/run-native-tests-aarch64.sh covers it by running the arm64 build under
# qemu.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cc=${CC:-cc}
cxx=${CXX:-c++}
out=${OUT:-"$root/scripts/out"}
objects="$out/obj"
mkdir -p "$objects"

# The C core, compiled as C.
# shellcheck disable=SC2086 # CC may carry flags, e.g. CC="zig cc".
$cc -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$root/app/src/main/cpp" \
    -c "$root/app/src/main/cpp/sha256_portable.c" -o "$objects/sha256_portable.o"
# shellcheck disable=SC2086
$cc -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$root/app/src/main/cpp" \
    -c "$root/app/src/main/cpp/sha256_dispatch.c" -o "$objects/sha256_dispatch.o"
# shellcheck disable=SC2086
$cc -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$root/app/src/main/cpp" \
    -c "$root/app/src/main/cpp/block_header.c" -o "$objects/block_header.o"

# Hash vectors and the block header path.
# shellcheck disable=SC2086
$cc -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$root/app/src/main/cpp" \
    -o "$out/hashes-native-test" \
    "$root/app/src/test/cpp/test_sha256.c" \
    "$objects/sha256_portable.o" "$objects/sha256_dispatch.o" "$objects/block_header.o"

"$out/hashes-native-test"

# The engine itself: threads, counters, the found-hash queue, mode and stop.
# shellcheck disable=SC2086
$cxx -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
    -I"$root/app/src/main/cpp" \
    -o "$out/hashes-miner-test" \
    "$root/app/src/test/cpp/test_miner.cpp" \
    "$root/app/src/main/cpp/miner.cpp" \
    "$objects/sha256_portable.o" "$objects/sha256_dispatch.o" "$objects/block_header.o"

"$out/hashes-miner-test"
