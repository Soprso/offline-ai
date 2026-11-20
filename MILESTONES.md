# Offline AI — Milestone Reports

Status values: `COMPLETE`, `NOT VERIFIED ON DEVICE`.

## Milestone A — Project skeleton and Compose host

**Status:** COMPLETE

- Created native Android project at `/home/madhumita/Offline AI`.
- Package/application id: `com.soprso.dhurandharai`.
- Pinned Gradle `9.4.1`, AGP `9.2.1`, JDK `17.0.20.1`.
- Added `MainActivity`, theme, launcher icon, manifest, and Compose host.
- Added drawer/navigation scaffold skeleton.
- Fixed compile error by changing a `Column` receiver to `ColumnScope`.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

## Milestone B — Terminal-inspired UI shell

**Status:** COMPLETE

- Implemented drawer destinations: Chat, History, Models, Settings.
- Implemented chat empty state, message composer, and placeholder screens.
- Avoided Material Icons dependency by using text glyphs.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 13s`.

## Milestone C — Local persistence with Room

**Status:** COMPLETE

- Added `Conversation`, `Message`, and `ModelMetadata` entities.
- Added DAOs, `AppDatabase`, schema export, and conversation CRUD.
- Wired History screen to real Room conversations (open/rename/delete).
- Message deletion cascades with conversations via foreign key.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 3m 37s`.

## Milestone D — Native foundation

**Status:** COMPLETE

- Added NDK/CMake configuration: NDK `27.1.12297006`, CMake `3.22.1`, `arm64-v8a`.
- Added `libofflineai.so` with JNI smoke bridge (`nativeAdd`, `nativeVersion`).
- Settings screen reports native build status and ABI.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 14s`.
- APK size at this checkpoint: `11,736,834` bytes.

## Milestone E — llama.cpp inference bridge

**Status:** COMPLETE

- Pinned llama.cpp tag `b12558`, commit `8bf88ba9368e70fe3ba1b0001b34e70ffc713716`.
- Statically linked llama.cpp into `libofflineai.so`.
- Implemented JNI: load/unload/isLoaded/contextLength/cancel/lastError/generate streaming.
- Used current llama.cpp API (`llama_batch_ext_*` + `llama_process`).
- Added Kotlin `NativeBridge`, `TokenListener`, and `ModelEngine`.
- Rewrote `ChatViewModel` for persistence plus generation.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 3m 11s`.
- Native artifacts: unstripped `libofflineai.so` 73,230,672 bytes; stripped packaged
  `libofflineai.so` 14,877,600 bytes; packaged `libomp.so` 961,440 bytes.
- Verified 9 JNI exports and 8,058 llama symbols.
- No responses are fabricated; with no model loaded, generation does not run.

## Milestone E Post-Rebuild — ViewModel compilation

**Status:** COMPLETE

- Rebuilt after the `ChatViewModel` rewrite.
- Only diagnostic: `ExperimentalCoroutinesApi` opt-in warning (later cleaned).
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 32s`.

## Milestone F — Streaming chat UI and local model library

**Status:** COMPLETE (device inference NOT VERIFIED)

- `ChatScreen` now consumes `viewModel.isGenerating`, `viewModel.streamingText`,
  `viewModel.error`, and `viewModel.stopGeneration()`.
- Live tokens render in a streaming bubble with a cursor; partial text is
  persisted when a turn completes, is cancelled, or fails.
- Chat header displays the loaded model, falling back to the first imported model.
- Added local model library:
  - Android document picker imports `.gguf` files into `filesDir/models`.
  - SHA-256 is computed during the single copy pass.
  - Room stores display name, file name, size, checksum, and import time.
  - Load/unload/delete controls enforce one loaded model at a time.
  - Deleting the active model is blocked; changing models during generation is blocked.
  - Import progress is shown with a determinate or indeterminate indicator.
- Added R8/JNI keep rules.
- **Build checkpoint:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 6s`.
- Final debug APK: `app/build/outputs/apk/debug/app-debug.apk` (`27,710,321` bytes).

## Milestone G — Integrated GGUF model manager

**Status:** COMPLETE (full device test matrix NOT run)

- Added versioned local catalogue (`ModelCatalog.VERSION = 1`) with seven curated
  entries. Five are revision-pinned direct HTTPS downloads with published
  SHA-256 checksums; two official Gemma 3 repositories are listed as gated and
  require license acceptance plus manual import.
- Added `DeviceInfoProvider` and `ModelCompatibilityChecker`. Estimates include
  weights, KV cache, 320 MiB runtime overhead and 192 MiB inference buffers;
  the memory budget uses total RAM capped/floored by currently available
  memory. No tokens-per-second claim is made.
- Migrated Room `models` schema 1 → 2 additively and added a `downloads` table.
  GGUF binaries remain outside SQLite.
- Added `ModelDownloadManager`: app-scoped coroutines, `.part` temp files,
  HTTP Range resume, progress/bytes/speed, pause/resume/cancel/retry, storage
  checks, size + SHA-256 verification, and safe restart reconciliation. An
  orphaned but verified final file is adopted on startup instead of being left
  as a stale `DOWNLOADING` record.
- Added `ModelRegistryRepository` and `ModelManagerViewModel` to unify
  catalogue, registry, downloads, compatibility and native load/unload/delete.
- Rewrote `ModelLibraryScreen` in the terminal style: ENGINE status, device
  RAM/storage line, RECOMMENDED section, AVAILABLE MODELS, compact rows,
  progress, gated/error notes, expandable bottom-sheet details, confirmation
  dialogs for replace-load and delete, and retained + ADD manual import.
- Updated the misleading network message to: "Models are stored on your device.
  Internet is used only to download models; AI inference runs locally."
- Chat header now shows only a model that llama.cpp actually loaded. Chat shows
  "No model loaded." / "Select a model to start chatting." with a MODELS button.
- Native unload now requests cancellation before freeing the model.
- Added pinned JUnit `4.13.2` and 9 local tests covering catalogue integrity
  and compatibility decisions.
- **Build checkpoints:**
  - `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL in 7m` (9 tests, 0 failures).
  - `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 9m 14s`.
  - Final `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL in 10s`.
- Final debug APK: `app/build/outputs/apk/debug/app-debug.apk`
  (`27,777,655` bytes).
- Device checks on Nothing Phone (1), serial `001206477007540`, Android 14,
  `arm64-v8a`, ~7.28 GiB total RAM:
  - New APK installed successfully.
  - Models screen renders the catalogue, device facts, gated labels, and
    recommendations.
  - Chat with no model shows the new banner and MODELS action.
  - A stale download record plus a fully verified orphaned Qwen2.5 0.5B GGUF
    was reconciled into an `INSTALLED` registry row on restart, with no partial
    download registered.
  - During the smoke check, Chat displayed `loaded · Qwen2.5 0.5B Instruct`
    and a completed assistant reply; the full 18-scenario test matrix was not
    executed by this implementation pass.

## Deferred / not yet verified

- Full 18-scenario device matrix, including deliberate download interruption,
  resume/cancel, insufficient-RAM load, offline chat after download, and
  delete/restart flows.
- Gated Gemma 3 direct downloads (license acceptance is intentionally outside
  this build; manual import remains the supported path).
- Tokens-per-second benchmarks (deliberately not claimed).
- R8 release build validation and instrumented migration tests.
- DataStore-backed settings.

No commit, push, publish, or deploy was performed at any milestone.
