# Offline AI

A privacy-focused, fully offline AI assistant for Android, powered by llama.cpp
and GGUF models.

Status: experimental / active development. Inference is local, but performance
is device-dependent and no benchmark numbers are claimed in this repository.

## Features

- Streaming local chat with one GGUF model loaded at a time.
- Model library with curated, revision-pinned, direct HTTPS GGUF downloads.
- Publisher SHA-256 verification before a downloaded model is registered.
- Manual GGUF import from device storage, including GGUF header inspection.
- Metadata-driven compatibility estimator; incomplete evidence reports UNKNOWN.
- Local conversation history with lazy rendering for long threads.
- Debug-only performance overlay with measured timings, thread counts and
  prompt cache reuse counters.
- Cancellation, KV-prefix reuse, and no cloud inference or account system.
- Gated model entries are discovery-only because this build has no sign-in.

## Technology stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.2.21 |
| UI | Jetpack Compose / Material 3, Navigation Compose |
| Persistence | Room 2.7.2 |
| Async | Kotlin coroutines and flows |
| Native engine | llama.cpp b12558, statically linked into `libofflineai.so` |
| Native build | NDK 27.1.12297006, CMake 3.22.1, ARM64 only |
| Build | Gradle 9.4.1, Android Gradle Plugin 9.2.1 |
| Android levels | minSdk 26, targetSdk 36 |

## Requirements

- Android Studio compatible with AGP 9.2.1 and Gradle 9.4.1.
- JDK 17.
- Android SDK 36, NDK 27.1.12297006 and CMake 3.22.1 installed.
- An `arm64-v8a` device or emulator. The native build targets ARMv8.2-A with
  dotprod and FP16, so generic ARM64 devices without those extensions may not
  run the library.
- Internet access only for first dependency resolution and model downloads.

## Android Studio setup

1. Clone this repository.
2. Open the `Offline AI` directory in Android Studio.
3. Let Gradle sync. Android Studio writes a local `local.properties` file,
   which is ignored by Git.
4. Select the `app` configuration and an ARM64 device.

## Build instructions

From the project root:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

Add `--offline` when the dependency cache is already populated.

Output paths:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Install the debug APK with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run unit tests with `./gradlew :app:testDebugUnitTest`. No instrumentation test
suite is configured in this repository.

## llama.cpp integration

The pinned llama.cpp source tree is vendored at:

```text
app/src/main/cpp/llama.cpp
```

It is a shallow clone of llama.cpp tag `b12558`, commit
`8bf88ba9368e70fe3ba1b0001b34e70ffc713716`. CMake builds it as static
libraries and links them into a single ARM64 `libofflineai.so`. The JNI bridge
lives in `app/src/main/cpp/inference/native_bridge.cpp` and uses the current
`llama_batch_ext_*` plus `llama_process` API.

The repository keeps the library source required by CMake (`ggml/`, `src/`,
`vendor/`, `include/`, `common/` and `cmake/`) but excludes upstream nested
`.git` metadata, build output, sample GGUF vocabularies, docs, tests, tools,
examples, benches and web UI. See `TOOLCHAIN.md` for exact versions and native
flags. To refresh llama.cpp, replace the vendored source with the same pinned
tag and re-run the build.

## GGUF model download and installation workflow

1. Open **Models** from the app navigation.
2. Select a downloadable catalogue entry and choose **DOWNLOAD**.
3. The app streams to a `.part` file, shows progress, checks the published
   SHA-256, then registers the installed model.
4. Choose **LOAD** to load the model into the native engine. Only one model is
   loaded at a time; loading another unloads the first but keeps its file.
5. Open **Chat** and send a message. Streaming output is rendered as tokens
   arrive.

Use **+ ADD** to import a GGUF file already on the device. Files are kept in
app-private storage and are never bundled into the APK or AAB. Model downloads
need internet access; after a model is installed, inference and conversations
work offline. Gated catalogue entries require license acceptance outside this
app and cannot be downloaded directly here.

## Privacy architecture

- No account, sign-in, telemetry or cloud inference service.
- `android.permission.INTERNET` is used only for HTTPS model downloads.
- `android:allowBackup="false"` keeps app data out of backups.
- Model weights are stored in app-private storage, not in the APK.
- Conversations are stored locally in Room. There is no sync service.

Data is protected by the Android app sandbox. It is not additionally encrypted
at rest, so rooted devices or filesystem-level access are outside the threat
model.

## Local conversation storage

Room database `offline_ai.db` stores conversations, messages, installed model
metadata and download records. Schema version is 3, with indices on
conversation timestamps and model lookup columns. The history screen renders
messages lazily. There is no remote conversation storage.

## Current limitations

- Experimental project; APIs, catalogue and native settings may change.
- CPU-only inference, ARM64 only, with no GPU acceleration.
- One loaded model at a time.
- Gated models are listed for discovery only.
- Model compatibility estimates can be UNKNOWN when GGUF metadata is missing.
- No verified prompt throughput, tokens/s, or thread-scaling numbers are
  claimed here. Measure on your own device.
- No Android instrumentation suite; only local JVM unit tests are configured.
- Long prompts are limited by the selected context length and device memory.
- A valid chat template is important for good manual-import results.

## Licence and acknowledgements

No project-level `LICENSE` file has been added yet. Add one before public
redistribution.

Third-party components keep their own licences:

- [llama.cpp](https://github.com/ggml-org/llama.cpp) - MIT; vendored source
  includes its upstream `LICENSE` file.
- Kotlin, AndroidX, Compose, Navigation and Room - Apache License 2.0.
- Model weights remain under their publishers' licences, including
  Apache-2.0, MIT, Llama 3.2 Community License, Qwen Research License and
  Gemma Terms of Use. Review the model details in the app before use.

See `MILESTONES.md` for development history and `TOOLCHAIN.md` for the frozen
toolchain and build records.
