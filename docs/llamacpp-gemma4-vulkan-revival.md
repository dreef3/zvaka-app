# llama.cpp Vulkan Gemma 4 Revival

This note captures the revival of the old `android/llamacpp` path after the
MediaTek LiteRT-LM NPU attempt stalled on compiled-bytecode compatibility.

## Why This Path Was Revived

- the app previously had a working `llama.cpp` Android integration with Vulkan
- public GGUF builds for `gemma-4-E2B-it` now exist
- no public Xiaomi HyperOS third-party Android LLM SDK was found that looked
  more viable than reviving the app's old local runtime

## Why The Repo Uses A `llama.cpp` Submodule

The Android `:llamacpp` module builds native code with CMake and links directly
against `llama.cpp`, `ggml`, and `mtmd` sources.

That source tree has to come from somewhere. The submodule is not conceptually
required, but it is the easiest way to keep the Android native build
reproducible.

Alternatives that would also work:

- a prebuilt AAR/JNI package checked into the repo or downloaded in CI
- a vendored tarball snapshot of `llama.cpp`
- a separate local checkout outside the app repo with custom CMake wiring

For this revival, we restored the historical submodule because it was the
fastest way to get the Vulkan build path back.

## Restored Pieces

Restored from the pre-removal app history:

- `.gitmodules`
- `android/settings.gradle.kts` include for `:llamacpp`
- `android/llamacpp/` Android library module
- `android/third_party/llama.cpp` historical submodule pin
- deleted Kotlin wrappers:
  - `chat/LlamaCppDietChatEngine.kt`
  - `inference/LlamaCppMultimodalFoodEstimationEngine.kt`
  - `inference/LlamaCppSmolVlmFoodEstimationEngine.kt`
  - `inference/SmolVlmFoodEstimationEngine.kt`
  - `inference/SmolVlmPocRunner.kt`

Current active integration goal:

- use the restored text-only path for coach chat with a Gemma 4 GGUF
- leave existing LiteRT photo estimation in place for now

## Current Gemma GGUF Descriptor

Current experimental coach model descriptor:

- file: `gemma-4-E2B-it-Q4_K_M.gguf`
- source: `https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf?download=true`
- size used by app metadata: about `3.1 GB`

The device-side download produced a final file around `2.88 GiB`, matching the
model card output size for that quant.

## App Wiring Added

New coach model option:

- `CoachModel.GemmaGguf`

Selected-model routing:

- `CoachModel.Gemma` -> existing LiteRT-LM path
- `CoachModel.GemmaGguf` -> restored `LlamaCppDietChatEngine`

Supporting additions:

- debug extras in `MainActivity` to:
  - set the selected coach model by storage key
  - trigger direct on-device model download via `ModelDownloader`
  - run a selected-engine smoke test
  - run a cold+warm double smoke test in one process

## Build Blockers Hit During Revival

### 1. Manifest minSdk mismatch

Problem:

- restored `:llamacpp` module declared `minSdk 34`
- app currently declares `minSdk 26`

Fix used:

- compile `:llamacpp` at `minSdk 30`
- add `tools:overrideLibrary="com.arm.aichat"` in the app manifest

Why:

- the current target device is far above API 30
- the Vulkan path is only intended for newer Android devices anyway

### 2. `mtmd` CMake configuration failure

Problem:

- the restored historical wrapper hit:
  - `set_target_properties called with incorrect number of arguments`

Cause:

- the pinned `llama.cpp` revision expected `LLAMA_INSTALL_VERSION` to already be
  defined for `tools/mtmd`

Fix used:

- define a fallback `LLAMA_INSTALL_VERSION` in the wrapper
  `android/llamacpp/src/main/cpp/CMakeLists.txt`

### 3. Nested Vulkan shader helper could not find `ninja`

Problem:

- the `ggml-vulkan` shader-generator host sub-build failed because `Ninja` was
  not found during the nested host configure step

Fix used:

- run Gradle with:
  - `PATH="/home/ae/android-sdk/cmake/3.22.1/bin:$PATH" ./gradlew installDebug`

This was enough for the nested host build to find Ninja.

### 4. Android logging API incompatibility

Problem:

- restored `logging.h` used `__android_log_is_loggable`
- that symbol is unavailable before API 30

Fix used:

- fallback to a simple `prio >= LOG_MIN_LEVEL` check when `__ANDROID_API__ < 30`

### 5. Missing Vulkan C++ header include path

Problem:

- `ggml-vulkan.cpp` could not find:
  - `vulkan/vulkan.hpp`

Fix used:

- inject the restored vendored Vulkan header directory into the `ggml-vulkan`
  target from the wrapper CMake after `add_subdirectory(${LLAMA_SRC} ...)`

### 6. Gemma 4 chat template abort

Problem:

- the model loaded, then aborted in native prompt formatting with:
  - `this custom template is not supported, try using --jinja`

Cause:

- the old wrapper forced `use_jinja = false`

Fix used:

- switch both text and multimodal wrappers to use Jinja when the loaded model has
  an explicit chat template

## Throughput Tuning Applied

The restored path used extremely conservative defaults:

- `n_threads = 1`
- `n_batch = 32`
- `n_ctx = 768`

Tuned values used for the Gemma GGUF experiment:

- `n_threads`: min `2`, max `4`
- `n_batch`: `128`
- `n_ctx`: `1024`

App-level Gemma GGUF engine tuning:

- `predictLength = 64`
- `generationTimeoutMs = 120_000`

## Device Validation Result

Device:

- Xiaomi `23078PND5G`
- HyperOS 3 / Android 16
- Vulkan backend reported by llama.cpp:
  - `Vulkan0 (Mali-G715-Immortalis MC11)`

Observed runtime behavior:

- model downloaded on-device successfully
- `llama.cpp` loaded the Gemma 4 GGUF successfully
- all layers were offloaded to Vulkan
- system prompt and user prompt both processed successfully
- model generation returned usable output

Measured smoke results after tuning:

- cold request:
  - total time: about `86.7s`
  - returned a response beginning with `<|channel>thought`
- warm request in the same loaded process:
  - total time: about `12.1s`
  - returned `OK`

This is materially better than the untuned restored path, where:

- cold request took about `136s`
- warm request still took about `35.9s`

## Current Interpretation

What is true now:

- `Gemma 4 E2B` can run in this app through restored `llama.cpp + Vulkan`
- the path is usable enough to answer on-device
- warm latency improved substantially after tuning

What is still not great:

- cold-start latency is still very high
- even warm latency is far from instant
- the first cold reply still included the model's thinking channel text instead
  of a clean final `OK`, so higher-level response cleanup may still be needed

## Most Useful Next Steps If We Continue This Path

1. Strip or disable thinking-mode output for Gemma 4 GGUF replies
2. Add a dedicated benchmark/debug action for prompt-processing and generation
   token rates instead of inferring from wall time
3. Test a smaller or more speed-oriented Gemma 4 quant if quality stays adequate
4. Decide whether the submodule should remain, or whether we should freeze this
   into a prebuilt JNI/AAR path
