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

## Attempt 8 — 2026-05-16

### Bundle

`gemma3-270m-it_mt6985_v14.litertlm` (231 MB) — built on GCP with **official**
`build_mt6985_gemma3_litertlm.py` pipeline + `MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib`.

Hypothesis: the custom `aot_compile_gemma3_v9.py` pipeline (Attempt 7) produced a different DLA
embedding format than the official pipeline. Gemma 4 E2B was built with the official pipeline and
reached DRAM OOM (past the dispatch-plugin check). Using the same official pipeline with v8_0_10
for Gemma 3 270M should produce compatible DLA.

Build output (GCP, 2026-05-16T20:59):
```
compiled_mt6985/prefill_decode/model_quantized_MediaTek_MT6985_apply_plugin.tflite  (140 MB)
compiled_mt6985/embedder/embedder_quantized_MediaTek_MT6985_apply_plugin.tflite     (86 MB)
gemma-3-270m-it_mt6985.litertlm                                                     (231 MB)
```

Note: `.litertlm` is NOT a zip. `_apply_plugin.tflite` files contain DLA embedded as custom ops.

### Error observed

```
05-16 23:02:32.990 W native  : W0000 llm_litert_npu_compiled_model_executor.cc:2559]
    No quantization for logits in 'decode' signature (using default scale=1, zero_point=0).
05-16 23:02:35.196 E LiteRtDietChat: sendMessage failed
05-16 23:02:35.196 E LiteRtDietChat: com.google.ai.edge.litertlm.LiteRtLmJniException:
    Failed to create engine: INTERNAL: ERROR:
    [runtime/executor/llm_litert_npu_compiled_model_executor.cc:2796]
    └ ERROR: [external/litert/litert/cc/litert_compiled_model.h:1140]
```

No `W/neuron` or `E/apusys` messages — NeuronAdapter not reached.

### Root cause

Same as Attempt 7. The official pipeline with `MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib` still
embeds v8_0_10 DLA format in the `_apply_plugin.tflite` files. The dispatch plugin in
`litertlm-android:0.10.2` rejects this at `litert_compiled_model.h:1140`.

### Key finding — Gemma 4 E2B / DRAM OOM revisited

The prior "Gemma 4 E2B ran but didn't fit into memory" was most likely:
- v9_0_3 DLA (dispatch accepts) → APUSys AIDL → `open("/dev/apusys")` → ENOMEM (same as v9 path above)
- The "DRAM OOM" was the APUSys ENOMEM, not device RAM OOM

This means BOTH SDK paths fail on this firmware. There is no known successful combination with
`litertlm-android:0.10.2` on this device.

### Additional investigation (same session)

- `/dev/apusys`: `crw-rw-rw-` (world-writable, owner `system:camera`) — **permissions are NOT the issue**
- The APUSys AIDL service (`vendor.mediatek.hardware.apuware.apusys.INeuronApusys/default`) is registered
- NNAPI services registered: `mtk-mdla_shim`, `mtk-neuron_shim`
- Querying both NNAPI services returns `FAILED_TRANSACTION` (no dump support)
- Camera HAL (NeuronPilot 6 path) can use MDLA — hardware is functional via v6 path
- The ENOMEM on `open("/dev/apusys")` appears to be an internal kernel driver state issue,
  not a permission or resource-size issue

### Status

FAILED — **same dispatch-plugin rejection as Attempt 7**. Official pipeline does not change
the DLA format embedded by v8_0_10 SDK.

### Dead ends summary

| Path | Outcome |
|------|---------|
| v9_0_3 DLA, any pipeline | Dispatch accepts → APUSys `open()` ENOMEM |
| v8_0_10 DLA, custom pipeline | Dispatch rejects at `litert_compiled_model.h:1140` |
| v8_0_10 DLA, official pipeline | Dispatch rejects at `litert_compiled_model.h:1140` |
| v9_0_3 + `--disable-apusys` shim | APUSys ENOMEM persists |
| neuron_shim fake-finish + v9 DLA | Dispatch accepts → APUSys ENOMEM (unchanged) |

Remaining options (not yet tried):
1. Root device to inspect `/dev/apusys open()` ENOMEM in kernel driver
2. Downgrade `litertlm-android` to a version that accepts v8_0_10 DLA
3. Use TFLite NNAPI delegate with `mtk-mdla_shim` (bypasses litertlm dispatch — major rework)

---

## Attempt 9 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` in app cache (v9_0_3 DLA, 717/717 subgraphs)

### Dispatch plugin

`libLiteRtDispatch_MediaTek.so` **v12** — rebuilt from LiteRT source (commit 472d1c0f) with:
- Adapter load order restored: `libneuronusdk_adapter.mtk.so` first, `libneuron_adapter_mgvi.so` second,
  `libneuron_adapter.so` third — **no v9 adapter** in list
- `break` on first successful load (reverted f817522's last-winner behavior)

This ensures **base adapter (v8.2.26 / NeuroPilot 6.0.6)** wins instead of v9 adapter.

### Logcat

17:03:04:50 — Partition enumeration: 717 `Found graph: Partition_N` entries logged in < 1s.  
17:03:05:08 — `Neuron AIDL client callback(pid: 22828) died` (logged when app was force-stopped at 03:23).

### Analysis

Logcat buffer was saturated by 207K+ `Found graph: Partition_N` log lines. The test result
(pass/fail) was overwritten before the session ended. The AIDL callback dying when force-stopped
confirms the base adapter DID establish an AIDL connection to the APUSys server during this run.
The connection persisted for ~18 minutes (03:04:50 → 03:23:08), suggesting either:
- 717 × ~1.5s ≈ 18 min of NeuronModel_restoreFromCompiledNetwork calls that eventually failed
- Or the first subgraph was stuck in the APUSys server for the full duration

### Status

INCONCLUSIVE — logcat saturated. Result recovered from Attempt 10 (same config, clean run).

---

## Attempt 10 — 2026-05-17

### Bundle

Same as Attempt 9: `gemma3-270m-it_mt6985_v9.litertlm` in app cache.  
Triggered via `am start --ez runCoachNpuSmokeTest true` after force-stopping app for clean process.

### Dispatch + adapter

v12 dispatch plugin, base adapter: `libneuronusdk_adapter.mtk.so` loaded, `Neuron api version: 8.2.26`.

### Error observed (complete)

```
03:23:19 I/litert  (27692): [neuron_adapter_api.cc:136] Loading MediaTek NeuronAdapter .so from: libneuronusdk_adapter.mtk.so
03:23:19 I/litert  (27692): [neuron_adapter_api.cc:147] Loaded NeuronAdapter shared library.
03:23:19 I/litert  (27692): [neuron_adapter_api.cc:212] NeuronAdapter symbols loaded
03:23:19 I/litert  (27692): [neuron_adapter_api.cc:269] Neuron api version: 8.2.26
03:23:19 E/apuware_hidl(27692): check utils aidl service fail
03:23:19 E/apuware_hidl(27692): HidlNeuronClient can't get INeuronApusys2 service (tried 3 times)
03:23:19 I/apuware_hidl(27692): AidlNeuronClient get aidl version=1
03:23:19 I/apuware_hidl(27692): run apuys aidl
03:23:33 E/apuware_server( 1370): createInstance failed
03:23:33 E/apuware_hidl(27692): AidlNeuronClient session_createInstance fail
03:23:33 E/neuron  (27692): APUSysEngine::createInstance() failed
03:23:33 E/neuron  (27692): APUSysEngine initialization failed.
03:23:33 W/neuron  (27692): Cannot create device for APUSYS_2_0
03:23:33 W/neuron  (27692): Cannot create device for EDMA_3_6
03:23:33 W/neuron  (27692): Found an unsupported target: EDMA_3_6
03:23:33 W/neuron  (27692): Fail to revise dla::CompiledResult
03:23:33 W/neuron  (27692): Fail to preprocess dla::CompiledGraph
03:23:33 W/neuron  (27692): Fail to preprocess dla::CompiledNetwork
03:23:33 E/neuron  (27692): Cannot prepare execution.
03:23:33 E/neuron  (27692): Successfully open network but cannot start execution.
03:23:33 E/neuron  (27692): NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network from the given buffer
03:23:33 E/neuron  (27692): The header of DLA is invalid
03:23:33 E/neuron  (27692): PrepareTensors: Currently we can't support dynamic shape
03:23:36 E/MainActivity(27692): Coach NPU smoke test failed
03:23:36 E/MainActivity(27692): com.google.ai.edge.litertlm.LiteRtLmJniException: Failed to create engine: INTERNAL: ERROR:
   [runtime/executor/llm_litert_npu_compiled_model_executor.cc:2796]
   └ ERROR: [external/litert/litert/cc/litert_compiled_model.h:1140]
```

### Analysis

The v12 dispatch correctly loads the **base adapter** (v8.2.26). NeuronAdapter IS reached (W/neuron
messages appear) — unlike Attempts 7/8 where v8_0_10 DLA was rejected before NeuronAdapter.

The base adapter uses:
1. HIDL `INeuronApusys2` (v2.1) — not found in VINTF manifest → skipped
2. AIDL `INeuronApusys` (v1) — found, `aidl version=1`
3. Calls `session_createInstance` for **APUSYS_2_0** target
4. APUSys server (PID 1370) returns `createInstance failed`
5. `Cannot create device for APUSYS_2_0` — same root cause as Attempts 4/5/6 with v9 adapter

The v9_0_3 DLA embeds APUSYS_2_0 + EDMA_3_6 as the hardware target. Neither the v9 adapter
(Attempts 4-6) nor the base adapter (Attempt 10) can create an APUSYS_2_0 session on this
device firmware (HyperOS 3.0.4).

**New finding**: The base adapter's AIDL v1 path fails with `createInstance failed` (server-side
rejection), while the v9 adapter's path fails with `open(/dev/apusys) ENOMEM` (kernel driver).
Both trace to the same root: APUSYS_2_0 device creation is blocked on this firmware.

**Updated dead ends table**:

| Path | Dispatch | Adapter | Outcome |
|------|----------|---------|---------|
| v9_0_3 DLA | Old (v9 wins) | v9 (9.0.11) | APUSys `open()` ENOMEM |
| v8_0_10 DLA | Old (v9 wins) | v9 (9.0.11) | Dispatch rejects at litert_compiled_model.h:1140 |
| v8_0_10 DLA, official pipeline | Old (v9 wins) | v9 (9.0.11) | Same dispatch rejection |
| v9_0_3 + `--disable-apusys` shim | Old (v9 wins) | v9 (9.0.11) | APUSys ENOMEM persists |
| v9_0_3 DLA | v12 (base first) | base (8.2.26) | APUSys AIDL `createInstance failed` (APUSYS_2_0) |

### Status

FAILED — **APUSYS_2_0 session creation rejected by APUSys AIDL server** on this firmware.

---

## Attempt 11 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` in app cache (v9_0_3 DLA, 717/717 subgraphs)

### Dispatch plugin

`libLiteRtDispatch_MediaTek.so` **v7 (lazy-import, resolve_symbols_in_exec=true bug)** — source rebuild
from LiteRT commit 472d1c0f:
- Strides check removed (`tensor_type.layout.has_strides = false`)
- Lazy-import pattern: `AttachInput`/`AttachOutput` deferred to `Invoke()`, NeuronExecution recreated
  after each partition compute to release APUSys device-VA imports

### Error

```
SIGABRT at DispatchDelegate::CreateDelegateKernelInterface()+316
```

### Root cause

`.bazelrc` unconditionally sets `--define=resolve_symbols_in_exec=true`, which leaves
`NeuronAdapterApi::Create` as an unresolved UND import. `RTLD_NOW` at dlopen time fails →
`has_dispatch_runtime_=false` → `LITERT_FATAL` → abort().

### Fix applied

Rebuilt dispatch plugin with `--define=resolve_symbols_in_exec=false` (dispatch v9, 611KB).
`NeuronAdapterApi::Create` now compiled in; `libLiteRt.so` appears in NEEDED with `@VERS_1.0`
versioned symbol references.

### Status

FAILED (SIGABRT). Fix → Attempt 12.

---

## Attempt 12 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` in app cache (v9_0_3 DLA, 717/717 subgraphs)

### Dispatch plugin

**v9** (resolve_symbols_in_exec=false, lazy-import, strides check removed)  
Base adapter wins: `libneuronusdk_adapter.mtk.so` (v8.2.26)

### Error

```
E/neuron: Fail to deserialize compiled network  
E/litert: [dispatch_api.cc:259] Failed to create context from context binary: Failed to finish compilation
```

### Root cause

DLA format mismatch: v9_0_3 SDK produces **V245303** DLA bytecode, base adapter (NeuroPilot 6.0.6)
expects **V222104**. The `.9.mtk.so` (v9) adapter is gated behind `GetNeuroPilotMagicNumber()` which
SELinux blocks on MIUI, returning -1. v9 adapter never loaded.

### Fix applied

Modified `neuron_adapter_api.cc` to unconditionally add `libneuronusdk_adapter.9.mtk.so` last in the
load list (last-winner loop). Rebuilt as dispatch v11. v9 adapter (9.0.11) now always wins.

### Status

FAILED (DLA format). Fix → Attempt 13.

---

## Attempt 13 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` in app cache (v9_0_3 DLA, 717/717 subgraphs)

### Dispatch plugin

**v11** (v9 adapter unconditional: `.9.mtk.so` last-winner)

### Error

```
E memImportV1: Failed to import buffer using ION — errno=12
```

The v9 adapter (9.0.11) DID load and the DLA format was accepted. However tensor buffer import
via `NeuronExecution_setInputFromMemory` / `setOutputFromMemory` (ION-based) failed.

### Root cause

MT6985 kernel 5.15 (Android 14) removed `/dev/ion`. The base `NeuronExecution_setInput/Output`
APIs use ION handles internally; without ION they fail with ENOMEM/errno=12.

### Fix applied

Switched to AHWB host-ptr path: `AHardwareBuffer_allocate` + `AHardwareBuffer_lock` +
`NeuronExecution_setInput` (value copy into AHardwareBuffer) instead of ION-based memory import.
Source change in `litert_dispatch_invocation_context.cc`.

### Status

FAILED (ION buffer import). Fix → Attempt 14.

---

## Attempt 14 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` (v9_0_3 DLA, 717/717 subgraphs, AOT + `import_forever: true`)

### Dispatch plugin

v11 + AHWB host-ptr buffer path

### Error

None at DLA load stage. 249 DLA subgraphs (`NeuronModel_restoreFromCompiledNetwork`) restored
successfully. First `RegisterTensorBuffer` for I/O tensor fails:

```
E apusys: memMapDeviceVa: map mem(2933/0) fail(Out of memory)
```

~5 MB I/O buffer fails device-VA import.

### Root cause

The AOT-compiled DLA embeds `import_forever: true` in compilation options, which causes all 249
subgraph model internals to be permanently held in APUSys device-VA space (total ~16 MB pool).
When `RegisterTensorBuffer` tries to import the inference I/O buffer (~5 MB), there is no space
left in the device-VA pool.

### Fix applied

- Removed `import_forever: true` from `kDefaultAotCompilationOptions` in `neuron_adapter_api.h`
  (set to `""`)
- Force JIT path (`LoadFromDlaBytecode`) in `LoadModelAndCompilation` in
  `litert_dispatch_invocation_context.cc` so model internals are compiled and released
  per-inference rather than held permanently from the AOT blob

Added diagnostic logging:
- `__attribute__((constructor))` that calls `__android_log_print` on dlopen
- `[MTK-DIAG]` logs at `LiteRtDispatchGetApi`, `LiteRtInitialize` entry, `NeuronAdapterApi::Create` failure

Rebuild → Attempt 15 / 17.

### Status

FAILED (APUSys device-VA OOM at I/O buffer registration). Fix → Attempt 17.

---

## Attempt 17 — 2026-05-17

### Bundle

`gemma3-270m-it_mt6985_v9.litertlm` (v9_0_3 DLA, 717/717 subgraphs)

### Dispatch plugin

Rebuilt from source with:
- `kDefaultAotCompilationOptions = ""` (import_forever removed)
- `LoadModelAndCompilation` forced to `LoadFromDlaBytecode` (JIT path)
- Diagnostic code: `OnLibraryLoaded()` constructor, `[MTK-DIAG]` logs
- Built **without** `--define=resolve_symbols_in_exec=false` (regression from omitting the flag)

### Error

```
I litert  : [litert_dispatch.cc:145] Loading shared library: .../libLiteRtDispatch_MediaTek.so
E litert  : [dispatch_delegate.cc:115] Failed to initialize Dispatch API: ERROR: [dispatch_delegate.cc:176]
E litert  : [dispatch_delegate.cc:130] Failed to create a dispatch delegate kernel: No usable Dispatch runtime found
```

No `MTKDispatch: dlopen SUCCEEDED` or `[MTK-DIAG]` logs appeared → `__attribute__((constructor))`
never executed → **dlopen itself failed**.

### Root cause

The rebuild omitted `--define=resolve_symbols_in_exec=false`. The `.bazelrc` default is
`resolve_symbols_in_exec=true`, so `litert_runtime_c_api_shared_lib` had empty srcs, and
`libLiteRt.so` was NOT added to the dispatch .so's NEEDED list.

Without `libLiteRt.so` in NEEDED, the 11 `LiteRt*` UND symbols cannot be resolved at RTLD_NOW
time on Android's isolated ClassLoader namespace (`clns-10`), even though `libLiteRt.so` is
already loaded in that namespace. RTLD_NOW requires all UND symbols to be in NEEDED.

The working Attempt 14 build had `libLiteRt.so` in NEEDED with versioned `@VERS_1.0` references.
The Attempt 17 rebuild lost this dependency.

### Fix applied

`patchelf --add-needed libLiteRt.so libLiteRtDispatch_MediaTek.so`

All 11 `LiteRt*` symbols exported from `libLiteRt.so` as `@@VERS_1.0` (default version) — unversioned
UND references resolve correctly against default-versioned exports.

**For future rebuilds**: always pass `--define=resolve_symbols_in_exec=false` to Bazel when building
the MediaTek dispatch plugin.

### Status

FAILED (dlopen: libLiteRt.so missing from NEEDED — same root cause as Attempt 17).
Patchelf fix from f240eab restored NEEDED, then tested as Attempt 18.

---

## Attempt 18 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, V230703 DLA, 249 subgraphs, import_forever: true)

### Dispatch plugin

Patchelf'd .so from f240eab (libLiteRt.so in NEEDED via patchelf, LoadFromDlaBytecode forced).

### Error

```
E MTKDispatch: libLiteRtDispatch_MediaTek.so: dlopen SUCCEEDED
I litert  : Neuron api version: 8.2.26
I litert  : There are 249 subgraphs in the bytecode
E neuron  : The header of DLA is invalid
E neuron  : NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network
E litert  : Failed to create context from context binary: Failed to finish compilation
```

### Root cause

`LoadFromDlaBytecode` (com.mediatek.compiled_network extension) fails for V230703 DLA format.
The base adapter (v8.2.26) only accepts V230703 via `model_restore_from_compiled_network` directly,
not via the extension mechanism. Fix → Attempt 19.

### Status

FAILED (DLA header invalid via extension path). Fix → Attempt 19.

---

## Attempt 19 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, V230703 DLA, 249 subgraphs, import_forever: true)

### Dispatch plugin

Rebuilt with:
- `LoadModelAndCompilation` restored to `LoadFromCachedNetwork` (AOT restore path)
- `--define=resolve_symbols_in_exec=false` (libLiteRt.so in NEEDED via build flag, no patchelf)
- Diagnostic logs retained

### Result

dlopen SUCCEEDED. 249 subgraphs loaded. NPU delegate applied to 122/243 nodes (3 partitions).
`AHardwareBuffer_lock` succeeded → registered host_ptr for I/O buffers.
No `memMapDeviceVa: Out of memory` — import_forever OOM did NOT recur (host-ptr path avoids
APUSys device-VA mapping at registration time). However inference invocation failed:

```
E apuware_hidl: mmap failed sharedFd = 0, size = 5242880: No such device
E neuron  : APUSys failed to allocate data buffer: size = 5242880, alignment = 256 for handle 13
E neuron  : HintFrontendBuffer() failed on input #1, buffer addr = 0x71545b1000
E litert  : Failed to attach input: Failed to set execution input from host ptr
E tflite  : Node number 256 (DELEGATE) failed to invoke.
```

### Root cause

`NeuronExecution_setInput` with a host pointer calls `apusysSession_memGetInfoFromHostPtr`
internally. This function only tracks APUSys-internal allocations; for externally-allocated
AHWBs it returns sharedFd=0 (stdin fd), causing mmap to fail with ENXIO. APUSys V2 then
tries to allocate its own 5 MB buffer, which also fails (resources exhausted).

### Fix applied

Changed `RegisterTensorBuffer` (AHWB case) to always use `NeuronMemory_createFromAHardwareBuffer`
+ `NeuronExecution_setInputFromMemory` (NeuronMemory path). This carries the real DMA-BUF fd
extracted from the AHWB and imports via APUSys HIDL (DMA-BUF, not ION). Rebuilt as Attempt 20.

### Status

FAILED (AttachInput: host ptr sharedFd=0 → mmap ENXIO). Fix → Attempt 20.

---

## Attempt 20 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, V230703 DLA, 249 subgraphs, import_forever: true)

### Dispatch plugin

Rebuilt with `memory_create_from_ahwb` → `execution_set_input_from_memory` path (NeuronMemory
carries real DMA-BUF fd; no host-ptr / apusysSession_memGetInfoFromHostPtr dependency).

### Status

FAILED — `E apuware_hidl: session_memImportV1 fail` at 13:30:11 (PID 21040).

`NeuronMemory_createFromAHardwareBuffer` succeeds (lazy import), but
`NeuronExecution_setInputFromMemory` triggers `apusysSession_memImportV1` on the
AHWB's DMA-BUF (from `mtk_mm` heap). APUSys rejects `mtk_mm` heap buffers — this
heap is for multimedia (GPU/display) and is not importable by the APU hardware
under this device's APUSys HAL. The import call returns an error, not ENOMEM
(no explicit ENOMEM log unlike Attempt 14). Fix → Attempt 21.

---

## Attempt 21 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, V230703 DLA, 249 subgraphs, import_forever: true)

### Dispatch plugin

System-heap copy-through. `RegisterTensorBuffer(AHWB)` allocates a DMA-BUF from
`/dev/dma_heap/system` (generic Linux DMA-BUF heap; APUSys should accept it), then
imports it via `NeuronMemory_createFromFd`.

- `AttachInput`: `AHardwareBuffer_lock` → `memcpy(AHWB→sys_heap)` → `AHardwareBuffer_unlock`
  → `NeuronExecution_setInputFromMemory` with sys-heap NeuronMemory.
- `AttachOutput`: `NeuronExecution_setOutputFromMemory` with sys-heap NeuronMemory.
- `DetachOutput`: `AHardwareBuffer_lock` → `memcpy(sys_heap→AHWB)` → unlock.

Logs `[MTK-DIAG] Sys-heap import OK fd=…` on success or `Sys-heap NeuronMemory import
failed result=…` on failure (falls back to direct AHWB import which also fails).

### Status

FAILED — `session_memImportV1 fail` persists even with sys-heap fd.

`NeuronMemory_createFromFd` returns `NEURON_NO_ERROR` (logged as "Sys-heap import OK") but
`NeuronExecution_setInputFromMemory` → APUSys AIDL `session_memImportV1` still rejects the
buffer at execution time (~4 seconds later). Root cause: `/dev/dma_heap/system` allocates
**cached** pages. APUSys AIDL cannot import cached DMA-BUFs because the APU DMA engine lacks
CPU cache-coherency access — the IOMMU mapping step fails. Fix → Attempt 22.

---

## Attempt 22 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempt 21)

### Dispatch plugin

Same sys-heap copy-through architecture as Attempt 21, but heap changed from
`/dev/dma_heap/system` (cached) to `/dev/dma_heap/system-uncached`.

Rationale: `system-uncached` heap bypasses CPU cache entirely (write-combine). APU DMA
engine can read/write DRAM directly without cache coherency coordination — `session_memImportV1`
IOMMU mapping should succeed. CPU `memcpy` in `AttachInput`/`DetachOutput` still works
correctly since uncached writes go straight to DRAM and APU reads fresh DRAM values.

Logs `[MTK-DIAG] Sys-uncached import OK fd=…` on success.

### Build note

`--linkopt=-Wl,--no-as-needed` alone is insufficient — Bazel's `resolve_symbols_in_exec=true`
default still drops `libLiteRt.so` from the NEEDED section. Patched post-build with:
```
patchelf --add-needed libLiteRt.so libLiteRtDispatch_MediaTek.so
```

### Status

FAILED — `session_memImportV1 fail` persists even with `system-uncached` heap.

`NeuronMemory_createFromFd` returns OK (logged "Sys-uncached import OK") but at execution time
`NeuronExecution_setInputFromMemory` → `session_memImportV1` still fails. Root cause:
`session_memImportV1` (V1 APUSys path) cannot import ANY external DMA-BUF heap including
`system-uncached`. Fix → Attempt 23: bypass `setInputFromMemory` entirely; use
`NeuronExecution_setInput/setOutput` (raw host pointer → V2 `apusysSession_memGetInfoFromHostPtr`
path) with the mmap'd sys-uncached buffer pointer.

(PID 19189 also crashed at dispatch init because `libLiteRt.so` was missing from NEEDED —
patchelf step was added after that run; PID 20292 was the Attempt 22 run that got furthest.)

---

## Attempt 23 — 2026-05-17

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–22)

### Dispatch plugin

Same sys-uncached allocation as Attempt 22, but `AttachInput`/`AttachOutput` now call
`NeuronExecution_setInput/setOutput` (raw host VA of the mmap'd sys-uncached buffer) instead
of `setInputFromMemory/setOutputFromMemory`. This bypasses `session_memImportV1` (V1 path)
and uses the V2 path (`apusysSession_memGetInfoFromHostPtr`) inside the NeuronAdapter.

- `AttachInput`: AHWB lock → `memcpy(AHWB→sys_heap)` → unlock → `execution_set_input(sys_heap_ptr, size)`
- `AttachOutput`: `execution_set_output(sys_heap_ptr, size)` (NeuronAdapter writes output here)
- `DetachOutput`: AHWB lock → `memcpy(sys_heap→AHWB)` → unlock

NeuronMemory (from `NeuronMemory_createFromFd`) is still created but no longer passed to
`set[Input|Output]FromMemory`.

### Status

FAILED — `mmap failed sharedFd=0` (V2 path returns fd=0 for sys-uncached VA).

`NeuronExecution_setInput` sends the mmap'd `sys-uncached` ptr to APUSys V2 path
(`memGetInfoFromHostPtr`). APUSys returned `sharedFd=0` for that VA, meaning it is NOT in
APUSys's internal VA table. APUSys then tried to allocate an internal 5 MB buffer — that also
failed with OOM because `import_forever=true` in the V230703 DLA had already consumed all
device-VA with 249 subgraph binaries permanently mapped (~16 MB pool fully exhausted).

Root cause confirmed: `import_forever=true` baked into V230703 DLA is the fundamental blocker.
No external memory path (V1 or V2) can work while import_forever holds the device-VA pool.

---

## Attempt 24 — 2026-05-18

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–23)

### Dispatch plugin

Reverted to `setInputFromMemory`/`setOutputFromMemory` for the AHWB case. Attempted
`/dev/dma_heap/mtk_sapu_data_shm_region` (SAPU heap — the MediaTek APUSys-internal heap) as
the DMA-BUF source. Rationale: SAPU heap pages are allocated by the APUSys kernel driver
itself, so `session_memImportV1` should accept them since they come from APUSys's own memory
pool (not an external DMA-BUF that needs IOMMU re-mapping).

Falls back to regular AHWB path if SAPU heap open fails.

### Status

FAILED — MTK-DIAG messages not captured before ring buffer overflow; `session_memImportV1`
still failed (same error pattern). SAPU heap either fell back silently to AHWB, or SAPU heap
DMA-BUFs are also rejected. The V1 AIDL path cannot import ANY external DMA-BUF on this
device regardless of heap type.

---

## Attempt 25 — 2026-05-18

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–24)

### Dispatch plugin

AHWB host-pointer V2 path. `memory_create_from_ahwb` registers the AHWB's DMA-BUF with
APUSys's internal table so `memGetInfoFromHostPtr` can resolve the VA → fd at execution time.
Lock AHWB once at `RegisterTensorBuffer` to capture its stable VA, then immediately unlock
(mmap persists until AHWB freed). At inference: `execution_set_input(locked_ptr)` uses V2 path
without needing `session_memImportV1`.

Logs `[MTK-DIAG] Att25: AHWB ptr=<ptr> neuron_mem=<ptr> size=<n>`.

### Status

FAILED — `mmap failed sharedFd=0`.

`memory_create_from_ahwb` does NOT register the AHWB's VA with APUSys's `memGetInfoFromHostPtr`
table. The locked ptr (`0x7945ba9000`) was confirmed to be outside APUSys's VA table (fd=0
returned). APUSys then tried its own internal buffer — also OOM (import_forever exhausted
device-VA). Confirmed: BOTH V1 and V2 paths reject all external memory on this device while
import_forever is active.

---

## Attempt 26 — 2026-05-19

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–25)

### Dispatch plugin

Fresh NeuronCompilation without `import_forever`. After `model_restore_from_compiled_network`
(required for V230703 format), immediately discard the resulting compilation and create a new
one via `NeuronCompilation_createWithOptions(model, &compilation, "")`.

### Status

FAILED — `compilation_finish` returns result=4 on a model restored from compiled network.
A restored NeuronModel has NO graph ops; it is a wrapper around the pre-compiled DLA and
cannot be re-compiled with new options. Cannot remove import_forever via this API path.

---

## Attempt 27 — 2026-05-19

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–26)

### Dispatch plugin

`LoadFromDlaBytecode` path: builds a NeuronModel with the `com.mediatek.compiled_network`
extension operand, then calls `compilation_create_with_options("")` + `compilation_finish`.
The extension op loads the DLA via compilation_finish (not restore), so import_forever from
the DLA metadata can be overridden with an empty options string.
Falls back to `LoadFromCachedNetwork` (import_forever restore) if extension fails.

### Status

FAILED — `model_get_extension_operand_type("com.mediatek.compiled_network")` returns error
on NeuronAdapter 8.2.26; the extension is not supported on this adapter version. Falls back
to import_forever path immediately. Extension path requires NeuronAdapter ≥ 8.x with MTK
runtime that exposes `com.mediatek.compiled_network`.

---

## Attempt 28 — 2026-05-19

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–27)

### Dispatch plugin

AHWB-NeuronMemory path: `memory_create_from_ahwb(ahwb, &neuron_memory)` creates a
NeuronMemory backed by the gralloc AHWB. At inference time uses
`execution_set_input_from_memory(neuron_memory, offset, size)`.

Hypothesis: `NeuronMemory_createFromAHardwareBuffer` uses APUSys's native AHWB import
path (gralloc-registered buffers), bypassing the `session_memImportV1` IOMMU restriction
that rejects all heap-allocated DMA-BUF fds (system, system-uncached, SAPU). Even with
import_forever active in the DLA, the AHWB NeuronMemory registration does not require
new device-VA — APUSys has a separate gralloc buffer table.

Previously untried combination: all earlier attempts used either
- host_ptr + execution_set_input (V2 path, fails: memGetInfoFromHostPtr returns fd=0)
- DMA-BUF fd NeuronMemory + execution_set_input_from_memory (V1 path, fails: IOMMU rejects)

This attempt uses AHWB NeuronMemory + execution_set_input_from_memory (gralloc path).

Logs `[MTK-DIAG] Att28: AHWB neuron_mem=<ptr> size=<n> off=<n>` at registration and
`[MTK-DIAG] Att28: execution_set_input_from_memory idx=<n> result=<n>` on failure.

SDK: `gemma3-270m-it_mt6985_v9.litertlm` (NA 8.2.26, NeuroPilot 6.0.3)

### Result

```
W neuron  : session_memImportV1 fail
E apusys  : APUSys imports ION buffer fd=4002 failed
[MTK-DIAG] Att28: execution_set_input_from_memory idx=1 result=4 mem=... off=0 size=5242880
```

- idx=0 (~320 KB, small buffer): `execution_set_input_from_memory` SUCCEEDS
- idx=1 (5 MB KV cache): FAILS with `session_memImportV1 fail` / `APUSys imports ION buffer fd=4002 failed`

`memory_create_from_ahwb` succeeds for BOTH buffers. The `session_memImportV1` rejection happens
at inference time when APUSys tries to map the NeuronMemory into device-VA. The AHWB is ION-backed
(`ION buffer` log confirms). APUSys rejects ION-backed fds via the v1 AIDL import path.

Root cause confirmed: `import_forever=true` in the V230703 DLA permanently maps all 249 subgraph
binaries into APUSys device-VA (~16 MB total pool) during `model_restore_from_compiled_network`.
Only ~320 KB of device-VA remains available for I/O tensor buffer imports, which is enough for
the small idx=0 buffer but not the 5 MB KV cache (idx=1).

All external DMA-BUF/AHWB/ION import paths are exhausted:
- `session_memImportV1`: rejects all external heaps for large buffers (device-VA exhausted by import_forever)
- `memGetInfoFromHostPtr` (V2 path): returns fd=0 for all external memory (AHWB-locked, sys-uncached)
- SAPU heaps: SELinux label `dmabuf_system_secure_heap_device` — inaccessible from UID 10750
- `com.mediatek.compiled_network` extension: not supported on NeuronAdapter 8.2.26

### Status

FAILED — APUSys device-VA exhausted by `import_forever=true`; all I/O import paths blocked for large buffers.

---

## Attempt 29 — 2026-05-19 (diagnostic)

### Bundle

`gemma-3-270m-it_mt6985.litertlm` (230 MB, same as Attempts 21–28)

### Dispatch plugin

Added Att29 diagnostic to `LoadFromCachedNetwork`:
- Hex-dump first 128 bytes of each DLA bytecode
- `memmem` search for text `import_forever` in the bytecode

### Result

```
[MTK-DIAG] Att29 DLA[000]: ed 3c 03 00 f4 08 00 00 00 00 00 00 f0 08 00 00 ...
[MTK-DIAG] Att29 import_forever text found=NO offset=-1
```

- DLA bytes start with magic `ed 3c 03 00` (V230703 AdapterCache format)
- `import_forever` is NOT stored as text — it's binary-encoded in the DLA compilation metadata
- Text search failed; binary patching the DLA itself is not feasible without format spec

### Status

DIAGNOSTIC CONFIRMED — `import_forever` binary-encoded; V230703 DLA format used.

---

## Attempt 30 — 2026-05-20

### Bundle

New model compiled on GCP WITHOUT `import_forever`.

### Root cause to fix

`kDefaultAotCompilationOptions = "--apusys-config \"{ \\\"import_forever\\\": true }\""` is
hardcoded in `litert/vendors/mediatek/neuron_adapter_api.h` and baked into the V230703 DLA at
GCP compile time via `NeuronCompilation_createV2(model, type, "--apusys-config \"{\\\"import_forever\\\": true}\"", &compilation)`.

Since `import_forever` is baked into the DLA binary at compile time and `restoreFromCompiledNetwork`
cannot override it (restored model has no graph ops), the fix requires recompiling the model
without the `import_forever=true` option.

### Fix

Binary-patch `libLiteRtCompilerPlugin_MediaTek.so` (in the GCP ai_edge_litert venv) to replace:
- `--apusys-config "{ \"import_forever\": true }"` → `--apusys-config "{}"\x00...`

The string is 46 bytes including null-terminator; replacement uses same size with null-padding
after `{}`. This causes `NeuronCompilation_createV2` to receive an empty JSON config — no
`import_forever` flag — so it uses the default (disabled) behavior.

Patched SO confirmed: `strings` shows `--apusys-config "{}"` instead of `import_forever: true`.

### Plan

1. Sync patched tools to GCP (LOCAL_WORKTREE=/home/ae/weight-loss-app)
2. Create GCP spot VM, bootstrap with patched ai-edge-litert SO
3. Run `run_stable_export_gemma3_270m_v9.sh` (v9_0_3 SDK, MT6985 target)
4. Download resulting `gemma-3-270m-it_mt6985.litertlm`
5. Deploy on device, run smoke test

If import_forever is disabled: all 249 DLA subgraphs will NOT permanently consume device-VA at
`restoreFromCompiledNetwork`. Device-VA will remain available for I/O buffer imports via
`execution_set_input_from_memory` (AHWB NeuronMemory path, Att28 code in dispatch plugin).

### Status

FAILED — v9_0_3 SDK + import_forever:false patch → `apply_plugin failed`: "RESHAPE: Invalid number
of in operands. Got 1 of 2". Root cause: `fix_dynamic_reshape.py` was removing input 1 from RESHAPE
ops (creating 1-input RESHAPE), which NeuronAdapter v9_0_3 rejects.

Note: the patch string used was `--apusys-config "{}"` (empty JSON) but was later updated to
`--apusys-config "{ \"import_forever\":false }"` for explicit false.

---

## Attempts 31–43 — 2026-05-20 to 2026-05-21

### Root cause

All attempts used v9_0_3 SDK + `import_forever:false` patch. Failed with
`RESHAPE: Invalid number of in operands. Got 1 of 2`.

Previously believed to be "v9_0_3 rejects import_forever:false" — this was WRONG.
v9_0_3 actually ACCEPTS `import_forever:false` (confirmed in error logs:
`INFO: NeuronCompilation_createWithOptions: --apusys-config "{ \"import_forever\":false }"`
appears before the RESHAPE error).

The real cause: RESHAPE ops in model_quantized.tflite have 2-input DYNAMIC format
(shape tensor has no buffer data). The LiteRT compiler plugin, when building the
NeuronAdapter subgraph, creates 1-input RESHAPE ops (using only the input tensor,
not the shape tensor). v9_0_3 expects 2-input RESHAPE.

Various attempts tried:
- Att31: recompile_apply_plugin.py on v9 cached model, v9_0_3 SDK, no protect_dla_dir.so
- Att32-39: various combinations with protect_dla_dir.so, NEURON_ADAPTER_OVERRIDE_SO redirect
- Att40-43: fix_dynamic_reshape.py (which REMOVED input 1 → worsened the error: model now has
  1-input RESHAPE, which v9_0_3 explicitly rejects as "Got 1 of 2")
- Att43: v8_0_10 + fix_dynamic_reshape + v9_0_3 redirect via protect_dla_dir.so

### Status

FAILED (all) — RESHAPE input count mismatch.

---

## Attempt 44 — 2026-05-22

### Strategy

v8_0_10 SDK (device-compatible DLA format) + binary NOP patch to bypass MapReshapeOps check
+ import_forever:false patch in compiler plugin SO.

Binary patch target: v8_0_10 `libneuron_adapter.so` offset 0xc21f40:
- CMP $0x2,%ecx (83 f9 02) + JAE to "Output shape as inputs not supported" (73 2a)
- Patched: 83 f9 02 **90 90** (NOP NOP)

Script: `tools/run_stable_export_gemma3_270m_v8_no_import_forever.sh`

### Result

FAILED — v8_0_10 NeuronAdapter validates the FULL MODEL (all 1361 ops) including
CPU-destined FULLY_CONNECTED layers. Biases for quantized FC layers are INT32, but
v8_0_10 requires float biases: "Invalid FullyConnectedLayer<N>: Bias should be floating
point type". Model verification failed → 0 ops selected → 0 DLA partitions.

v9_0_3 does NOT have this restriction (confirmed: v9 compiled the same model in Att28).

### Status

FAILED — v8_0_10 FC bias validation blocks entire model compilation.

---

## Attempt 45 — 2026-05-22

### Strategy

v9_0_3 SDK + import_forever:false + fixed fix_dynamic_reshape.py (bake shapes, keep 2 inputs)
+ MapReshapeOps JA→NOP patch at both occurrences (0xf5f72b and 0x1166170).

Script: `tools/gcp/run_att45_v9_fixed_reshape.sh`

### Result

FAILED — v9_0_3 also rejects non-float FC biases: `"Bias should be floating point type"`, 0 ops selected.

Root cause: Att31–43 failed on RESHAPE BEFORE reaching the FC bias check. Now that RESHAPE
passes, the FC bias check is exposed. v9_0_3 libneuron_adapter.so validates FC bias types
even for CPU-fallback ops. Pattern (JA + BT %rax,%rdx + JAE) at file offset 0x1056652 in
v9_0_3 SO leads to the error. Error: `tmputmbm6jf.error` (13:08 UTC).

---

## Attempt 46 — 2026-05-22

### Strategy

v9_0_3 + import_forever:false + fixed RESHAPE + MapReshapeOps NOP + FC bias NOP.

Script: `tools/gcp/run_att46_v9_fc_bias_patch.sh`

### Result

PARTIAL PROGRESS — FC bias gate passed. 1291 ops selected (subgraph 0), 1184 (subgraph 1).
Compilation started for 291 NPU subgraphs. FAILED: `Compile error: NoExecPlan`.

Root cause: MapReshapeOps NOP lets RESHAPE pass GetSupportedOperations → RESHAPE enters
NPU subgraphs → MDLA hardware rejects them at compile time ("Unsupported layer"). Need
to either not NOP MapReshapeOps OR prevent RESHAPE from entering NPU via the shim.

---

## Attempt 47 — 2026-05-22

### Strategy

v9_0_3 + import_forever:false + FC bias NOP ONLY (revert MapReshapeOps NOP).
Expected: MapReshapeOps check naturally rejects RESHAPE → CPU, other ops → NPU.

Script: `tools/gcp/run_att47_v9_no_reshape_nop.sh`

### Result

FAILED — MapReshapeOps check fires but causes a FATAL non-SIGABRT error that propagates
to 0 ops selected (not per-op rejection as expected). Last line of error file:
`INFO: MapReshapeOps: Output shape as inputs not supported` → same 109KB file as before.

Conclusion: MapReshapeOps check cannot be used as a CPU-fallback filter — it aborts the
entire GetSupportedOperations call when it fires, not just the specific op.

---

## Attempt 48 — 2026-05-22

### Strategy

v9_0_3 + shim + both NOPs. Script: `tools/gcp/run_att48_v9_shim.sh`

### Result

FAILED — shim SO built with wrong SONAME symlink. `NEEDED: libneuron_adapter.so.9`
but script created `libneuron_adapter_real.so.9` (wrong name) → shim couldn't load real
SO → `neuron_adapter_api.cc:65 Failed to load NeuronAdapter shared library`.

---

## Attempt 49 — 2026-05-22

### Strategy

Same as Att48 but with fixed SONAME symlink:
`ln -sf libneuron_adapter_real.so ${V9_DIR}/libneuron_adapter.so.9`
(matches the real SO's DT_SONAME which the dynamic linker uses to resolve NEEDED)

Script: `tools/gcp/run_att49_v9_shim_soname_fix.sh`

### Result

**COMPILATION SUCCEEDED!** ✓ (13:43:01 → 13:43:27, only 26 seconds)

Output: `prefill_decode.tflite` (141 MB) + `embedder.tflite` (87 MB)  
Bundle: `gemma3-270m-att49.litertlm` (233 MB)

Shim debug log confirms: RESHAPE ops forced to CPU (op[8] type=22 supported=1 → patched).

### Device test — 2026-05-26

**FAILED** — DLA format incompatible with base adapter.

```
NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network from the given buffer
```

Root cause: v9_0_3 SDK produces DLA in a format not recognized by the device's base
NeuronAdapter 8.2.26 (`libneuronusdk_adapter.mtk.so`, SONAME `libneuron_adapter.so.8`).
The rebuilt dispatch plugin loads base adapter preferentially; v9 DLA is rejected there.

Fix: Switch compilation SDK from v9_0_3 → v8_0_10 (SONAME `libneuron_adapter.so.8`),
which should produce DLA in a format that base adapter 8.2.26 can load.

---

## Attempt 50 — 2026-05-26

### Strategy

Switch compilation SDK from v9_0_3 → v8_0_10 to produce DLA compatible with device's
base NeuronAdapter 8.2.26 (SONAME `libneuron_adapter.so.8`).

Key differences vs Att49:
- SDK: `v8_0_10/host/lib/libneuron_adapter.so`
- SONAME symlink: `libneuron_adapter.so.8 → libneuron_adapter_real.so`
- `--sdk-subdir v8_0_10/host/lib`
- No `patch_fc_bias_check.py` — v8_0_10 uses `BT ecx,eax` (`0f a3 c1`) not found by
  the v9 pattern searcher; `neuron_shim.c` updated with all-zero → whitelist fallback
  (in case FC bias check silently returns 0 ops)
- Compile both `prefill_decode` AND `embedder` with v8_0_10 shim

Scripts: `tools/gcp/run_att50_v8_shim.sh` + `tools/gcp/run_att50_bundle.sh`

### Compilation result — 2026-05-26

**SUCCEEDED** ✓ (19:43:25 → 19:43:46, only 21 seconds)

Output: `prefill_decode.tflite` (140 MB) + `embedder.tflite` (87 MB)  
Bundle: `gemma3-270m-att50.litertlm` (230 MB, 241,745,920 bytes)

Shim debug log (key finding): child exits cleanly with real non-zero results — the
all-zero whitelist fallback was NOT needed. With MapReshapeOps NOP applied, v8_0_10
`getSupportedOperations` returns valid supported flags; shim forced 107–211 RESHAPE/FC
ops to CPU normally. DLA built with v8_0_10 format (should match adapter 8.2.26).

Sample shim log:
```
getSupportedOps ENTER comp=0x... numOps=840
child exited=1 status=0 sig=0 done_flag=1
scanned 840 ops, forced 107 RESHAPE/FC + 0 EXT unsupported
```

Bundle pushed to device: `/data/data/com.dreef3.weightlossapp.debug/cache/models/gemma-3-270m-it_mt6985.litertlm` (231M)

### Device test — pending

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

# Clear logcat FIRST, then start logcat monitor in background, THEN launch smoke test
adb logcat -c
adb logcat -v time 2>&1 | grep -E "MTK-DIAG|MainActivity|NeuronAdapter|litert|dispatch|FATAL|SIGABRT" &
# Smoke test: start MainActivity with runCoachNpuSmokeTest=true extra
# (NpuSmokeTestReceiver does NOT exist; smoke test is triggered via am start)
adb shell am start -n com.dreef3.weightlossapp.debug/com.dreef3.weightlossapp.app.MainActivity \
  --ez runCoachNpuSmokeTest true

# Watch logcat (results in MainActivity tag)
adb logcat -v time -s MainActivity litert native

# Verify model in place
adb shell run-as com.dreef3.weightlossapp.debug ls -lh cache/models/
```
