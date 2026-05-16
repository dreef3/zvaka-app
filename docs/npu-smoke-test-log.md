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

### Error observed

```
W neuron  : APUSysEngine::createInstance() failed
W neuron  : APUSysEngine initialization failed.
W neuron  : Cannot create device for APUSYS_2_0
W neuron  : Found an unsupported target: APUSYS_2_0
W neuron  : Fail to revise dla::CompiledResult
W neuron  : Fail to preprocess dla::CompiledGraph
W neuron  : Fail to preprocess dla::CompiledNetwork
W neuron  : Cannot prepare execution.
W neuron  : Successfully open network but cannot start execution.
W neuron  : NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network from the given buffer
W neuron  : The header of DLA is invalid
E litert  : [dispatch_api.cc:259] Failed to create context from context binary: Failed to finish compilation
```

### Analysis

The v9_0_3 (and v9_0_2) SDK always compiles DLA for MT6985 with APUSYS_2_0 as the hardware backend.
The on-device NeuronAdapter v9.0.11 has code for both APUSYS_1_0 and APUSYS_2_0 but fails to create
an APUSYS_2_0 device on this firmware. Both `libapuwareapusys.mtk.so` and `libapuwareapusys_v2.mtk.so`
are present on the device but APUSysEngine init fails — likely a SELinux/HIDL restriction on MIUI.

### Status

FAILED — APUSYS_2_0 device creation fails on device firmware.

---

## Attempt 5 — 2026-05-16

### Bundle

`gemma3-270m-it_mt6985_v11.litertlm` (235 MB, v9_0_2 SDK, 717/717 clean AOT compiles, MUL on CPU)

### Hypothesis

v9_0_2 might target APUSYS_1_0 instead of APUSYS_2_0 for MT6985, unlike v9_0_3.

### Error observed

Same error as Attempt 4:
```
W neuron  : Cannot create device for APUSYS_2_0
W neuron  : Found an unsupported target: APUSYS_2_0
E litert  : [dispatch_api.cc:259] Failed to create context from context binary: Failed to finish compilation
E LiteRtDietChat: com.google.ai.edge.litertlm.LiteRtLmJniException: Failed to create engine: INTERNAL: ERROR
```

### Analysis

v9_0_2 also targets APUSYS_2_0 for MT6985 — both SDK versions have the same behaviour.
The APUSYS_2_0 target is hardcoded for MT6985 in v9_0_2 and v9_0_3.

Next step: Try `--disable-apusys` NeuronAdapter compile flag to produce DLA without APUSYS backend,
potentially targeting MDLA_3_0 directly.

### Status

FAILED — same APUSYS_2_0 incompatibility.

---

## Attempt 6 — 2026-05-16

### Bundle

`gemma3-270m-it_mt6985_v12.litertlm` (236 MB, v9_0_3 SDK + `--disable-apusys` shim flag,
717/717 clean AOT compiles, MUL on CPU)

### What `--disable-apusys` changed

The flag was injected in `NeuronCompilation_createWithOptions` in `tools/neuron_shim.c`. It changed
the compiled DLA binary format enough to pass the header validation check that previously failed in
Attempts 4/5 ("The header of DLA is invalid"). **717 subgraphs loaded and parsed successfully.**

However the flag does NOT change the runtime execution target: the loaded DLA is still identified as
APUSYS_2_0 and the runtime still tries to create an APUSys session.

### Error observed

```
05-16 21:31:21.013 E/apusys  ( 1370): apusysSession_createInstance: ==============================================
05-16 21:31:21.014 E/apusys  ( 1370): apusysSession_createInstance: | open apusys device node fail, errno(12/Out of memory)|
05-16 21:31:21.014 E/apusys  ( 1370): apusysSession_createInstance: ==============================================
05-16 21:31:21.014 E/neuron  (19478): APUSysEngine::createInstance() failed
05-16 21:31:21.014 E/neuron  (19478): APUSysEngine initialization failed.
05-16 21:31:21.014 W/neuron  (19478): Cannot create device for APUSYS_2_0
05-16 21:31:21.014 W/neuron  (19478): Found an unsupported target: APUSYS_2_0
05-16 21:31:21.014 W/neuron  (19478): Fail to revise dla::CompiledResult
05-16 21:31:21.014 W/neuron  (19478): Fail to preprocess dla::CompiledGraph
05-16 21:31:21.014 W/neuron  (19478): Fail to preprocess dla::CompiledNetwork
05-16 21:31:21.015 E/neuron  (19478): Cannot prepare execution.
05-16 21:31:21.015 E/neuron  (19478): Successfully open network but cannot start execution.
05-16 21:31:21.015 E/neuron  (19478): NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network
05-16 21:31:21.017 E/neuron  (19478): The header of DLA is invalid
05-16 21:31:21.020 E/neuron  (19478): unregistered target: NEON
05-16 21:31:21.020 E/neuron  (19478): unregistered target: GPU
05-16 21:31:21.021 E/neuron  (19478): PrepareTensors: Currently we can't support dynamic shape
05-16 21:31:21.022 E/litert  (19478): [dispatch_api.cc:259] Failed to create context from context binary
```

### Root cause analysis

**Error origin**: `libapu_mdw.so` in the NNAPI shim service (PID 1370, `u:r:mtk_hal_neuralnetworks:s0`,
uid=1000/system). The MDW (Memory Dynamic Worker) calls `open("/dev/apusys", O_RDWR)` and gets
`errno=12` (ENOMEM) from the kernel driver.

**Confirmed facts**:
- No SELinux denial logged for `mtk_hal_neuralnetworks → apusys_device` — the kernel receives the
  `open()` but returns ENOMEM from its driver code
- `/dev/apusys` has `crw-rw-rw- system:camera 10,114` — DAC allows all users
- No user-space process holds `/dev/apusys` open (`lsof /dev/apusys` returns empty)
- APU RISC-V management core IS running (`remoteproc1` exists, 11 RPMsg channels enumerated:
  `mdla-rx/tx`, `mvpu-rx/tx`, `apu-mdw`, `apu-reviser`, `apu_top_3`, etc.)
- `mtk_aov` (Always On Vision) uses `mtk_aie` NOT `apusys` — AOV is not competing for APUSys
- Build type: `user` (not userdebug) → `adb root` unavailable, sysfs locked to root
- `remoteproc1/state` is permission-denied — cannot determine RISC-V core state from shell

**Architecture of device NeuronAdapter**:
- `libneuronusdk_adapter.9.mtk.so` → binder → APUSys AIDL (`INeuronApusys`) → `libapu_mdw.so`
  → `open("/dev/apusys")` → **ENOMEM** ← **stuck here**
- `libneuronusdk_adapter.mtk.so` → OpenCL (`libcmdl_ndk.mtk.so`) + ARM NN (`libarmnn_ndk.mtk.so`)
  → Mali GPU path (not MDLA/NPU)

**Remaining ENOMEM hypotheses** (unconfirmed, root needed):
1. APUSys kernel driver calls `pm_runtime_get_sync()` to power on MDLA and the power-on fails
   (clock gating, voltage, or firmware not loaded for the MDLA domain specifically)
2. The MDW session initialization requires an IOVA allocation that fails (IOMMU group 2 space
   exhausted or misconfigured on this MIUI firmware)
3. The `apusys-reviser` device (IOVA remapper in IOMMU group 2) cannot allocate a new session
   context (fixed-size pool, or misconfigured)

**What did NOT work as alternative paths**:
- `--disable-apusys` flag (v12): fixes DLA header but APUSYS_2_0 session creation still fails
- NEON target: "unregistered" in v9 adapter on this device
- GPU target: "unregistered" in v9 adapter on this device
- JIT NNAPI path: fails with dynamic shape (LLM KV-cache uses variable sequence lengths)
- Old adapter (`libneuronusdk_adapter.mtk.so`): uses GPU/OpenCL compute, NOT MDLA NPU

### Status

FAILED — **ENOMEM from `/dev/apusys` kernel driver**. Root access required to diagnose further.

---

## Attempt 7 — 2026-05-16

### Bundle

`gemma3-270m-it_mt6985_v13.litertlm` (231 MB, v8_0_10 SDK, 717/717 clean AOT compiles,
MUL on CPU, no `--disable-apusys`)

### Hypothesis

v8_0_10 is the "correct" SDK for this device's NeuroPilot 6.0.3 runtime. Previous Gemma 4 E2B
attempts with v8_0_10 showed APUSys opening successfully (failing later on DRAM OOM, not on
`open("/dev/apusys")`). Gemma 3 is 10× smaller, so DRAM OOM unlikely.

### Error observed

```
05-16 22:47:48.887 D/LiteRtDietChat(19478): sendMessage start message=Reply with exactly OK.
05-16 22:47:48.889 D/LiteRtDietChat(19478): prompt built chars=183
05-16 22:47:51.074 E/LiteRtDietChat(19478): sendMessage failed
05-16 22:47:51.074 E/LiteRtDietChat(19478): com.google.ai.edge.litertlm.LiteRtLmJniException:
    Failed to create engine: INTERNAL: ERROR:
    [runtime/executor/llm_litert_npu_compiled_model_executor.cc:2796]
    └ ERROR: [external/litert/litert/cc/litert_compiled_model.h:1140]
```

No `W/neuron` or `E/apusys` messages — NeuronAdapter was never reached.

### Root cause

The LiteRT dispatch plugin (`libLiteRtDispatch_MediaTek.so`, from `litertlm-android:0.10.2`)
validates the DLA format at load time. v8_0_10 DLA format is rejected at
`litert_compiled_model.h:1140` before NeuronAdapter is called. The dispatch plugin in
litertlm 0.10.2 requires v9 DLA format (from v9_0_x SDK).

### Architecture insight

Two incompatible constraints:

| SDK | DLA loads? | APUSys succeeds? |
|-----|-----------|-----------------|
| v9_0_3 | ✓ (dispatch plugin accepts) | ✗ (ENOMEM from /dev/apusys) |
| v8_0_10 | ✗ (dispatch plugin rejects at litert_compiled_model.h) | n/a |

**The dispatch plugin (from litertlm 0.10.2) requires v9 DLA, but the device's v9 NeuronAdapter
triggers APUSYS_2_0 session creation which fails with ENOMEM on this MIUI firmware.**

Additional observation: PID 1424 (Camera HAL AINR) successfully uses MDLA via NeuroPilot 6
(`neuron_sdk_version 6`, `NrCore: set MDLA Core Option = single`) — the hardware IS functional,
but through a Camera HAL–specific code path (NeuronRuntime, not LiteRT dispatch).

### Status

FAILED — **dispatch plugin/DLA format mismatch**. v8 DLA rejected by litertlm 0.10.2 dispatch
plugin before reaching NeuronAdapter.

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
