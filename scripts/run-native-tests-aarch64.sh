#!/bin/sh
# Build the same C tests for arm64-v8a and run them under qemu.
#
#   CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
#   QEMU_AARCH64=/path/to/qemu-aarch64-static \
#   ./scripts/run-native-tests-aarch64.sh
#
# Why this exists: the ARMv8 SHA2 path cannot run on an x86_64 host, and the one
# thing that must not reach a phone untested is whether its four-round intrinsic
# sequence really is the FIPS 180-4 compression. The crypto object is compiled
# with -march=armv8-a+crypto, exactly as CMakeLists.txt does for the APK, the
# rest of the code with plain armv8-a; then the tests compare the accelerated
# compression against the portable one on every block they hash.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cc=${CC:-}
qemu=${QEMU_AARCH64:-qemu-aarch64-static}
out=${OUT:-"$root/scripts/out"}
work="$out/aarch64"

if [ -z "$cc" ]; then
    echo "Set CC to an aarch64 clang driver (for example the NDK's aarch64-linux-android26-clang)." >&2
    exit 2
fi

mkdir -p "$work"

common="-DHASHES_HAVE_ARM64_SHA2=1 -std=c11 -O2 -Wall -Wextra -Werror -I$root/app/src/main/cpp"

# Only this object gets the extension, mirroring the per-file flag in CMake.
# shellcheck disable=SC2086
$cc -march=armv8-a+crypto $common -c "$root/app/src/main/cpp/sha256_arm64_crypto.c" \
    -o "$work/sha256_arm64_crypto.o"

for source in \
    app/src/test/cpp/test_sha256.c \
    app/src/main/cpp/sha256_portable.c \
    app/src/main/cpp/sha256_dispatch.c \
    app/src/main/cpp/block_header.c
do
    name=$(basename "$source" .c)
    # shellcheck disable=SC2086
    $cc -march=armv8-a $common -c "$root/$source" -o "$work/$name.o"
done

# shellcheck disable=SC2086
$cc -static -o "$work/hashes-native-test-arm64" \
    "$work/test_sha256.o" "$work/sha256_portable.o" "$work/sha256_dispatch.o" \
    "$work/block_header.o" "$work/sha256_arm64_crypto.o"

"$qemu" -cpu max "$work/hashes-native-test-arm64"
