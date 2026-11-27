# Hashes native engine.
#
# The one rule this file exists to enforce: -march=armv8-a+crypto is applied to
# sha256_arm64_crypto.c and to nothing else. An instruction from the ARMv8
# SHA-2 extension executed on a core that does not have it is SIGILL at run
# time, so the accelerated code is kept in its own translation unit, compiled
# with the extension, and reached only through the runtime HWCAP_SHA2 check in
# sha256_dispatch.c.
cmake_minimum_required(VERSION 3.22.1)
project(hashes C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Reproducible build: no path from the build host may end up in the objects.
set(HASHES_PREFIX_FLAGS
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${HASHES_PREFIX_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${HASHES_PREFIX_FLAGS}")

add_library(hashes SHARED
    jni_bridge.cpp
    miner.cpp
    block_header.c
    sha256_dispatch.c
    sha256_portable.c)

target_compile_options(hashes PRIVATE
    -O3
    -fvisibility=hidden
    -fstack-protector-strong
    -Wall
    -Wextra
    -Werror)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set_source_files_properties(sha256_arm64_crypto.c
        PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
    target_sources(hashes PRIVATE sha256_arm64_crypto.c)
    target_compile_definitions(hashes PRIVATE HASHES_HAVE_ARM64_SHA2=1)
endif()

find_library(ANDROID_LOG_LIB log)
if(ANDROID_LOG_LIB)
    target_link_libraries(hashes PRIVATE ${ANDROID_LOG_LIB})
endif()
