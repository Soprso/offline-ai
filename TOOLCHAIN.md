# Offline AI — Frozen Toolchain

This file is the single human-readable record of the toolchain used by this
project. Machine-readable versions live only in `gradle/libs.versions.toml`;
no dependency or plugin uses a dynamic (`+`, `latest`, range) version.

## Host / build environment

| Item | Exact value |
|---|---|
| JDK | `17.0.20.1` (Ubuntu `17.0.20.1+1-1-26.04-Ubuntu`) |
| Gradle wrapper | `9.4.1` (`gradle-9.4.1-bin.zip`) |
| Gradle embedded Kotlin | `2.3.0` |
| AGP | `9.2.1` |
| Kotlin / Compose plugin | `2.2.21` |
| KSP | `2.3.10` |
| Room Gradle plugin | `2.7.2` |

AGP 9's built-in Kotlin support is used. The
`org.jetbrains.kotlin.android` plugin is deliberately **not** applied; the
Kotlin Compose plugin is applied at the same `2.2.21` version.

## Android / native

| Item | Exact value |
|---|---|
| `compileSdk` | `36` |
| `targetSdk` | `36` |
| `minSdk` | `26` |
| NDK | `27.1.12297006` (pinned; AGP 9 default NDK 28.2 is not installed) |
| CMake | `3.22.1` |
| ABIs | `arm64-v8a` only (target: Nothing Phone (1)) |
| STL | `c++_static` |

## App dependencies (single source: `gradle/libs.versions.toml`)

| Dependency | Version |
|---|---|
| Compose BOM | `2025.10.01` |
| Compose UI (resolved from BOM) | `1.9.4` |
| Material 3 (resolved from BOM) | `1.4.0` |
| androidx.core:core-ktx | `1.16.0` |
| androidx.activity:activity-compose | `1.10.1` |
| androidx.lifecycle | `2.9.4` |
| androidx.navigation:navigation-compose | `2.9.8` |
| androidx.room | `2.7.2` |
| KSP Room compiler | `2.3.10` |
| JUnit (local JVM tests) | `4.13.2` |

Compose BOM `2025.10.01` requires `minCompileSdk 35`; `compileSdk 36` is
installed and used. No Material Icons artifact is included; compact text
glyphs are used for controls.

## Native inference

| Item | Exact value |
|---|---|
| llama.cpp tag | `b12558` |
| llama.cpp commit | `8bf88ba9368e70fe3ba1b0001b34e70ffc713716` |
| Source location | `app/src/main/cpp/llama.cpp` (shallow clone) |
| Shared library | `libofflineai.so` |
| JNI exports | `9` |
| Native API | `llama_batch_ext_*` + `llama_process` (current API, not deprecated `llama_decode`) |
| OpenMP | `GGML_OPENMP OFF`; no `libomp.so` is packaged |
| ARM codegen | `GGML_NATIVE OFF`, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` |
| Native flags | `-O3` for native targets; `-Wl,--gc-sections` when linking `libofflineai.so` |
| Context | `n_batch=512`, `n_ubatch=512`, `n_seq_max=1`, F16 KV, CPU-only (`n_gpu_layers=0`) |
| Model loading | `LLAMA_LOAD_MODE_MMAP` (mmap on, mlock off) |
| Prompt cache | Longest still-valid token prefix is reused; cache is invalidated on cancel/error |
| Metrics | 17 long values; indices 15 and 16 are processed and reused prompt tokens |

llama.cpp is statically linked into `libofflineai.so`; no remote inference,
cloud model, or online fallback exists. OpenMP is disabled because llama.cpp
uses its own `n_threads` pool; this also removes `libomp.so` from release
artifacts. The explicit ARM target keeps the known-good dotprod/FP16 codegen,
but generic ARM64 devices without those extensions may be unable to run the
native library. No device was attached, so the target CPU features and runtime
performance were not measured.

Prompt throughput now divides by `prompt_processed_tokens`, not the full prompt
token count. A reused prefix is counted separately in `prompt_reused_tokens`;
no cache reuse is claimed unless the native memory ranges were validated. KV
reuse is conservative for sliding-window caches because a nonzero `pos_min`
forces invalidation. Performance remains UNKNOWN until measured on a device.

## Build checkpoints

All final commands below were run with `--offline`.

| Checkpoint | Result |
|---|---|
| A — skeleton | `BUILD SUCCESSFUL` (after `ColumnScope` fix) |
| B — Compose UI | `BUILD SUCCESSFUL in 13s` |
| C — Room persistence | `BUILD SUCCESSFUL in 3m 37s` |
| D — JNI/NDK foundation | `BUILD SUCCESSFUL in 14s` |
| E — llama.cpp integration | `BUILD SUCCESSFUL in 3m 11s` |
| E post-rebuild | `BUILD SUCCESSFUL in 32s` |
| F — streaming UI + model library | `BUILD SUCCESSFUL` |
| G — model manager (compile) | `./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL` |
| G — model manager (tests) | `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL` (9 tests, 0 failures) |
| G — model manager (APK) | `./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Final catalogue/native/Room unit tests | `./gradlew :app:testDebugUnitTest --offline` → `BUILD SUCCESSFUL` (31 tests, 0 failures, 0 errors, 0 skipped) |
| Final debug APK, ARM64 release APK, AAB | `./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease --offline` → `BUILD SUCCESSFUL` |

## Notes

- One version catalog (`gradle/libs.versions.toml`) is the only dependency
  source of truth.
- Model catalogue `VERSION = 3`: 22 entries, of which 20 are ungated direct
  downloads and 2 are gated Gemma discovery-only entries. Fifteen entries were
  added. Each addition is revision-pinned and carries the publisher LFS
  SHA-256, published file size, GGUF header dimensions, and a chat template;
  direct URLs returned HTTP 200, matching `Content-Length`, with
  `Accept-Ranges: bytes`. Architectures covered by the additions are `qwen2`,
  `qwen3`, `llama`, `phi3`, and `granitemoe`, all present in bundled llama.cpp.
- Excluded from the final shortlist: Phi-2 (no `tokenizer.chat_template`),
  StableLM (`license=other`), and OLMo 2 (`license=None`). No new gated models
  were added.
- Compatibility remains metadata-driven. Installed GGUF header values take
  precedence over the pinned catalogue profile; unreadable manual imports stay
  UNKNOWN. No hardcoded RAM estimator or invented performance prediction was
  added. Performance status remains UNKNOWN because no device benchmark ran.
- App/runtime changes kept the existing screens, navigation, typography,
  colours, and download workflow unchanged. Room is at version 3 with
  `MIGRATION_2_3`, indices on conversation timestamps and model lookup columns,
  and a direct `touchUpdatedAt` query. The download verifier uses a 256 KiB
  buffer. R8 keep rules preserve JNI entry points and callbacks. Long chat
  histories continue to use Compose `LazyColumn`, so messages are rendered
  lazily.
- GGUF weights are never packaged in the APK or AAB; they remain in app-private
  storage after download or manual import.

Final artifacts were built from this tree and copied to `/tmp/opencode/final/`.
Baseline artifacts are in `/tmp/opencode/baseline/`.

| Artifact | Baseline size | Final size | Delta | Final SHA-256 |
|---|---:|---:|---:|---|
| Debug APK | 27,723,064 | 27,723,064 | 0 | `7609eef24f1da5ec5f2bedb2c78c4473c086184de95c08611248eeda78889eb1` |
| ARM64 release APK | 9,299,835 | 8,343,398 | -956,437 (-10.3%) | `f15a540e877b2dfe3ece6119dfd27b249c2906f93d0e441825d9df03be547a9f` |
| Release AAB | 9,880,963 | 9,165,060 | -715,903 (-7.2%) | `1848f619ff7a4acee653cd5332e93f0354b299652bc44606f7f63639cd1f72dc` |

- Final debug APK: `app/build/outputs/apk/debug/app-debug.apk`, also copied to
  `/tmp/opencode/final/app-debug.apk`, 27,723,064 bytes. Its ZIP size is
  unchanged from baseline, but `libofflineai.so` shrank by 51,864 bytes and
  several DEX files grew.
- Final ARM64 release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
  Final AAB: `app/build/outputs/bundle/release/app-release.aab`.
- Release APK/AAB changes include removal of `libomp.so` (961,440 bytes
  uncompressed) and its AAB debug-symbol sidecar. R8 also renamed a generated
  service file from `META-INF/services/et` to `META-INF/services/ft`; no source
  API changed. No APK or AAB contains a `.gguf` file.
- Verification status: 31 JVM unit tests pass; Gradle debug/release/AAB builds
  pass. `adb devices -l` listed no device, so on-device inference, startup,
  stability, download/install, and 2/3/4-thread benchmarks were not measured.
  No instrumentation test suite is configured. The ARM dotprod/FP16 requirement
  was not hardware-verified, and full model files were not downloaded or
  installed on a device during this pass.
- No commit, push, publish, or deploy has been performed.
