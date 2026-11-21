cmake_minimum_required(VERSION 3.22.1)
project(offlineai LANGUAGES C CXX)

# ---- llama.cpp (pinned release tag b12558, see TOOLCHAIN.md) ---------------
# Built as static libs and linked into our single shared library so the APK
# ships exactly one .so for arm64-v8a.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL       OFF CACHE BOOL "" FORCE)  # no OpenSSL on-device
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)  # portable ARM64 build
set(GGML_CPU_ARM_ARCH   "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)  # llama.cpp uses its own thread pool
set(GGML_BLAS           OFF CACHE BOOL "" FORCE)
set(GGML_CUDA           OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN         OFF CACHE BOOL "" FORCE)
set(GGML_METAL          OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU         OFF CACHE BOOL "" FORCE)

# AGP's Debug configuration supplies -g but no optimization flag. Keep native
# inference optimized in debug APKs too, matching the previous known-good build.
add_compile_options(-O3)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

# ---- Our JNI bridge -------------------------------------------------------
add_library(offlineai SHARED
    inference/native_bridge.cpp
)

find_library(log-lib log)

target_compile_features(offlineai PRIVATE cxx_std_17)
target_compile_options(offlineai PRIVATE -fno-exceptions -fno-rtti -O3)
# 16 KB page size compatibility (Google Play requirement, Nov 2025+).
# NDK r27 LLD defaults to 4 KB max-page-size; set 16 KB explicitly so all
# LOAD segments in the final shared library are 16 KB-aligned. This covers
# code linked from static llama.cpp/ggml archives into this .so.
target_link_options(offlineai PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(offlineai
    PRIVATE
        llama
        ggml
        ggml-base
        ${log-lib}
)
