# weight-loss-app Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-03

## Active Technologies
- Kotlin 2.x + Android Gradle Plugin, Jetpack Compose, Navigation Compose, AndroidX ViewModel, Room, DataStore, CameraX, LiteRT-LM Android, Gemma 4 E2B model asset (001-calorie-photo-tracker)
- Room for structured local data, DataStore for lightweight preferences, app-private file storage for captured photos/model assets (001-calorie-photo-tracker)

## Project Structure

```text
android/
specs/
```

## Commands

cd android && ./gradlew installDebug
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew connectedDebugAndroidTest

## Code Style

Kotlin 2.x: Follow standard conventions

## Recent Changes
- 001-calorie-photo-tracker: Switched planning to native Android with Kotlin, Compose, Room, CameraX, and on-device Gemma via LiteRT-LM

<!-- MANUAL ADDITIONS START -->
- Never run more than one Gradle build at a time on this machine. Before starting any `./gradlew ...` command, verify there is no other active Gradle wrapper build for the current user.
- Use Conventional Commits / semantic commit messages for all commits in this repo. Prefer formats like `feat: ...`, `fix: ...`, `refactor: ...`, `docs: ...`, `ci: ...`, and use `!` or `BREAKING CHANGE:` for breaking changes.
- Local device connection details and Android SDK paths live in `.env.local` at the repo root.
- The cloud upload backend that receives model-improvement photo uploads lives outside this repo at `~/zvaka-backend`; when changing client upload auth, keep the backend verifier in sync.
- After making Android app changes, always install the app on a connected device if one is available. Use the SDK and device connection details from `.env.local` when needed.
- Do not run local unit tests or instrumentation tests on this machine. Raise a PR and rely on GitHub Actions for test execution instead.
- Local Android validation on this machine should be limited to install flows such as `cd android && ./gradlew installDebug`.
- Prefer GitHub Actions for all release APK/AAB builds on this machine; avoid local `assembleRelease`, `bundleRelease`, or `installRelease` unless explicitly required.
## NPU Smoke Test (MT6985 / feat/litertlm-npu-setting)

**Working branch**: `feat/litertlm-npu-setting` checked out in the main repo at `~/weight-loss-app`.
Do NOT use worktrees for this branch — they cause wrong nativeLibraryDir and missing smoke test wiring.

**Model on device**: `/data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm` (243 MB)
**App expected path**: `<appCacheDir>/models/gemma-3-270m-it_mt6985.litertlm`

### One-time model setup (after fresh install)
```bash
adb -s $ADB_DEVICE_ADDRESS shell run-as com.dreef3.weightlossapp.debug mkdir -p cache/models
adb -s $ADB_DEVICE_ADDRESS shell run-as com.dreef3.weightlossapp.debug \
  cp /data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm \
     cache/models/gemma-3-270m-it_mt6985.litertlm
```

### Run unattended smoke test via intent
```bash
adb -s $ADB_DEVICE_ADDRESS shell am start \
  -n com.dreef3.weightlossapp.debug/com.dreef3.weightlossapp.app.MainActivity \
  --ez runCoachNpuSmokeTest true
```
Then watch logcat:
```bash
adb -s $ADB_DEVICE_ADDRESS logcat -v time -s MainActivity LiteRtConversationRunner litert native
```
Success: `Coach NPU smoke test succeeded:` in logcat.
Failure: `Coach NPU smoke test failed` with stack trace.

### Document all attempts
Every smoke test run (pass or fail) must be recorded in `docs/npu-smoke-test-log.md`.
Include: date, APK commit/branch, error message, root cause, fix applied.

### Known v9 AOT compile issues (resolved or in progress)
See `docs/npu-smoke-test-log.md` and memory `project_neuron_shim` for full history.
- Original crash: "Cannot duplicate a non-owned tensor buffer" → fixed: externalize_embedder=True
- AOT INT32 RESHAPE → NoExecPlan → fixed: neuron_shim.c fake-finish
- FC bias rejection → fixed: exclude FC (type=9) from NPU whitelist
- MUL+CONCAT subgraphs (nops≤3, RoPE-related) → `NeuronCompilation_finish` NoExecPlan →
  fake-finish produced **0-byte DLA blobs** → on device:
  `NeuronCompilation_restoreFromCompiledNetwork(size=0)` crashes with "incorrect size" →
  whole NPU context creation fails (not a graceful CPU fallback).
  Fix: remove MUL(18) from NPU whitelist → these ops go to CPU from the start.
  Status: **AOT re-compile in progress on GCP (v10)**.

### Current v9/v10 status (2026-05-16)
Smoke test Attempt 3 FAILED: DLA deserialization — 0-byte DLA blobs from 2 fake-succeed
subgraphs (`op_types: 22 18 2` and `18 2`, i.e. RESHAPE+MUL+CONCAT and MUL+CONCAT).
GCP re-compile in progress with MUL removed from neuron_shim NPU whitelist.
**Blocker**: waiting for compiled_v10 to finish, then re-bundle and smoke test Attempt 4.

<!-- MANUAL ADDITIONS END -->
