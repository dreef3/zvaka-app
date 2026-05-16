# NPU Smoke Test Log — Gemma 3 270M MT6985 v9

Branch: `feat/litertlm-npu-setting`  
Device: Xiaomi 13T Pro (MT6985 / Dimensity 9200)  
ADB: `100.104.183.118:5555` (Tailscale)

---

## Attempt 1 — 2026-05-16

### Setup

- Model: `gemma3-270m-it_mt6985_v9.litertlm` (243 MB, externalize_embedder=True)
- Pushed to: `/data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm`
- Native libs pushed: `libneuron_graph_delegate.mtk.so`, `libneuron_wrapper.so` → `/data/local/tmp/`

### APK installed

Built from `feat/home-widget` branch (wrong) — had hardcoded `nativeLibraryDir = "/data/local/tmp"` and
hardcoded `modelPath = "/data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm"` in `LiteRtConversationRunner.kt`.

### Crash observed

```
E litert  : [litert_dispatch.cc:107] No dispatch library found in /data/local/tmp
E litert  : [dispatch_delegate.cc:114] Failed to initialize Dispatch API
E litert  : [dispatch_delegate.cc:129] Failed to create a dispatch delegate kernel: No usable Dispatch runtime found
F libc    : Fatal signal 6 (SIGABRT)
```

### Root cause

`libLiteRtDispatch_MediaTek.so` is bundled in the APK's `jniLibs/arm64-v8a/` but the hardcoded
`nativeLibraryDir = "/data/local/tmp"` caused LiteRT to look there instead of the app's native lib
directory. The dispatch plugin was never in `/data/local/tmp`.

---

## Attempt 2 — 2026-05-16

### Fix

Rebuilt from the correct branch: `feat/litertlm-npu-setting`. This branch uses:
- `nativeLibraryDir = context.applicationInfo.nativeLibraryDir` — correct app lib path
- `modelStorage.fileFor(ModelDescriptors.gemma3Mt6985Coach)` — model at `<cacheDir>/models/gemma-3-270m-it_mt6985.litertlm`
- `selectedCoachNpuSmokeTestEngine` in `AppContainer` for the UI smoke test button

Added `NpuSmokeTestReceiver` for unattended ADB-triggered testing:
```
adb shell am broadcast -a com.dreef3.weightlossapp.NPU_SMOKE_TEST com.dreef3.weightlossapp.debug
```
Watch logcat tag `NpuSmokeTest` for `SMOKE_TEST_PASSED` or `SMOKE_TEST_FAILED`.

Model file must be placed at:
```
<appCacheDir>/models/gemma-3-270m-it_mt6985.litertlm
# i.e., push via:
adb shell run-as com.dreef3.weightlossapp.debug mkdir -p cache/models
adb shell run-as com.dreef3.weightlossapp.debug cp /data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm cache/models/gemma-3-270m-it_mt6985.litertlm
```

### Result: FAILED — wrong model selected

`CoachModel.Gemma3Mt6985` was missing from the enum. `fromStorageKey("gemma3_mt6985")` returned the
default `CoachModel.Gemma` → engine loaded `gemma-4-E2B-it.litertlm` (2.4 GB) which has no NPU
signatures.

**Error**: `NOT_FOUND: Signature not found` — the Gemma-4 LiteRT model has no `prefill_*` NPU
signatures.

**Fix applied**: Added `Gemma3Mt6985` to `CoachModel` enum, `ModelDescriptors`, `CoachModelSupport`,
`SelectableDietChatEngine`, and `AppContainer`. Re-built and installed.

---

## Attempt 3 — 2026-05-16

### What was working

- Correct model loaded: `gemma-3-270m-it_mt6985.litertlm` (243 MB) confirmed by logcat
- Correct `nativeLibraryDir` (app lib path) → dispatch library found OK
- Bundle is v9 (externalize_embedder=True); prefill signature has `embeddings` float input ✓
- AOT compiled on GCP with v9_0_3 MediaTek SDK; 536/538 MDLA subgraphs OK, 2 fake-succeed (0-byte DLA)

### Error observed

```
E neuron  : Fail to deserialize compiled network (version: 1048588)
E neuron  : NeuronCompilation_restoreFromCompiledNetwork incorrect size after model is deserialized
E neuron  : The buffer of loaded network is a nullptr
E neuron  : Cannot set a nullptr compiled network.
E neuron  : NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network from the given buffer
E neuron  : PrepareTensors: Currently we can't support dynamic shape, the dimension size of a tensor Operand type 3 should not be zero
E litert  : [dispatch_api.cc:259] Failed to create context from context binary: Failed to finish compilation
E MainActivity: Coach NPU smoke test failed
E MainActivity: com.google.ai.edge.litertlm.LiteRtLmJniException: Failed to create engine: INTERNAL: ERROR:
   [runtime/executor/llm_litert_npu_compiled_model_executor.cc:2796]
```

Device NeuronAdapter version: **9.0.11**. AOT compile used: **v9_0_3 SDK**.

### Analysis

1. **`version: 1048588`** — in hex `0x10000C`. MediaTek DLA format version embedded in the serialized
   blob header. The device's NeuronAdapter 9.0.11 parser rejects a blob it doesn't recognise.

2. **`incorrect size after model is deserialized`** — after reading the header version, the serialized
   size field doesn't match the actual buffer size. Possibly because the 2 fake-succeed subgraphs have
   0-byte DLA payloads: when the runtime expects a non-zero-byte compiled network and finds 0 bytes, the
   size check fails immediately.

3. **`dynamic shape, tensor Operand type 3 should not be zero`** — this is the fallback path: the
   runtime tries to re-JIT compile the failed subgraph from the NeuronModel (NNAPI graph), but the
   graph has dynamic tensor shapes (the LM uses variable sequence lengths) that NeuroPilot 6 cannot JIT.

### Root cause candidates (in order of likelihood)

A. **The 2 fake-succeed 0-byte DLA subgraphs** crash the `restoreFromCompiledNetwork` size check. Even
   though 536 subgraphs are valid, the first encounter of a 0-byte DLA entry corrupts the session.
   → Fix: rebuild on GCP **skipping** (CPU-fallback) those 2 subgraphs instead of fake-succeeding.

B. **v9_0_3 SDK DLA format ≠ NeuronAdapter 9.0.11 on-device format**. The version tag `1048588` is
   not recognised. Even perfectly compiled subgraphs fail to deserialize.
   → Fix: find the exact SDK version used to build NeuronAdapter 9.0.11 on this device and use that
   SDK for AOT compilation; or use v9_0_11 SDK if available.

### Fix applied

Removed MUL(18) from the NPU whitelist in `tools/neuron_shim.c`. MUL ops now run on CPU
from the start — they are never assigned to NPU during `getSupportedOperations`, so no
0-byte DLA blobs are written.

Re-ran AOT compilation on GCP → `compiled_v10/` (2026-05-16):
- **717/717 subgraphs compiled OK** (0 FAILED, 0 fake-succeed)
- Main LLM compiled TFLite: 144 MB
- Re-bundled as `gemma3-270m-it_mt6985_v10.litertlm` (246 MB)

### Status

FAILED — DLA deserialization. **v10 bundle produced and fetched.** Push pending (device on cellular).

---

## Attempt 4 — 2026-05-16

### Bundle

`gemma3-270m-it_mt6985_v10.litertlm` (246 MB, 717/717 clean AOT compiles, MUL on CPU)

### Setup

Waiting for device to switch to WiFi before pushing 236 MB bundle.

---

## Key model facts

| Item | Value |
|------|-------|
| Model bundle | `gemma3-270m-it_mt6985_v9.litertlm` (243 MB) |
| On-device path | `/data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm` |
| App expected path | `<cacheDir>/models/gemma-3-270m-it_mt6985.litertlm` |
| App model descriptor filename | `gemma-3-270m-it_mt6985.litertlm` |
| NPU whitelist | ADD/MUL/RESHAPE(float)/SOFTMAX/MEAN/SUB/TRANSPOSE/GREATER/BATCH_MATMUL |
| FC on CPU | Yes — patcher dummy-bias causes MDLA rejection |
| DLA subgraphs | 536/538 OK, 2 fake-succeed (MUL broadcast shape) |
| Main LLM DLA | 141 MB |
| Embedder DLA | 87 MB |
| `externalize_embedder` | True — prefill_128 uses `embeddings` (float) input |

---

## Useful commands

```bash
# Build and install NPU branch
cd /home/ae/weight-loss-app/android && ./gradlew installDebug

# Push model (WiFi only — check cellular first)
adb shell run-as com.dreef3.weightlossapp.debug mkdir -p cache/models
adb shell run-as com.dreef3.weightlossapp.debug cp /data/local/tmp/gemma3-270m-it_mt6985_v9.litertlm cache/models/gemma-3-270m-it_mt6985.litertlm

# Run smoke test
adb shell am broadcast -a com.dreef3.weightlossapp.NPU_SMOKE_TEST -n com.dreef3.weightlossapp.debug/.app.NpuSmokeTestReceiver

# Watch logcat
adb logcat -v time -s NpuSmokeTest LiteRtConversationRunner litert native

# Verify model in place
adb shell run-as com.dreef3.weightlossapp.debug ls -lh cache/models/
```
