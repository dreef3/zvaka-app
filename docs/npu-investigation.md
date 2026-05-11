# NPU Inference Investigation — Gemma 4 E2B on Xiaomi 13T Pro (MT6985)

**Goal:** Make on-device NPU inference work via LiteRT-LM + NeuroPilot on MT6985 (Dimensity 9200+).

---

## Device

- Xiaomi 13T Pro, MT6985 (Dimensity 9200+), MIUI/HyperOS
- NeuroPilot 6.0.3 on device: `libneuron_runtime_vpp.6.0.3.so`
- APUSys / DLA co-processor for on-device ML acceleration
- ADB over WiFi: `192.168.0.5:38047` (port changes after reboot)

---

## Problem 1: prefill_32 Signature Mismatch

### Root Cause

`llm_litert_npu_compiled_model_executor.cc` had all 8 prefill-related constants hardcoded to `_128` / `128`:

```cpp
constexpr char kPrefillSignature[] = "prefill_128";
constexpr int kPrefillSize = 128;
// ... etc.
```

The Gemma 4 E2B `.litertlm` file exported from the LiteRT-Torch pipeline uses `prefill_32` signatures (not `prefill_128`). The executor never found the right signature and failed immediately.

### Fix

Patch all 8 constants in `llm_litert_npu_compiled_model_executor.cc` to use `_32` / `32`:

```cpp
constexpr char kPrefillSignature[] = "prefill_32";
constexpr int kPrefillSize = 32;
static constexpr absl::string_view kPrefillEmbedder = "prefill_embedder_32";
static constexpr absl::string_view kPrefillEmbedderPerLayer = "prefill_per_layer_embedder_32";
static constexpr absl::string_view kPrefillMask = "prefill_mask_32";
static constexpr absl::string_view kPrefillRope = "prefill_rope_32";
static constexpr absl::string_view kPrefillLlm = "prefill_32";
static constexpr absl::string_view kPrefillCacheUpdate = "prefill_cache_update_32";
```

**Status: Patched and verified** (for prefill_32 exports). `strings` on `liblitertlm_jni.so` confirms symbols.

**Update (2026-05-08):** Reverted JNI back to the committed (prefill_128) version for Attempt 4.  
Current worktree JNI: `prefill_128` (restored via `git checkout HEAD -- android/app/src/main/jniLibs/arm64-v8a/liblitertlm_jni.so`).  
The prefill_32 patch (uncommitted) was discarded; it's documented here if needed again.

### Where

`/home/ae/src/LiteRT-LM/runtime/executor/llm_litert_npu_compiled_model_executor.cc`

The JNI lives at: `android/app/src/main/jniLibs/arm64-v8a/liblitertlm_jni.so`  
- Committed (HEAD) version: `prefill_128` — use with `--prefill-lengths 128` exports  
- The prefill_32 patch: locally applied (see Problem 1 fix above) — use with `--prefill-lengths 32` exports

---

## Problem 2: APUSys Unique Shared DRAM OOM

### Root Cause (confirmed 2026-05-09)

`CompiledModel::Create` calls into NeuroPilot, which maps the transformer's DLA working tensor into APUSys device VA (`mem_type=0 DRAM, mem_flag=1 unique`). This device-VA pool is limited on MIUI/HyperOS firmware.

Kernel-level error chain for every failed attempt (from `logcat -b all`):
```
W BpBinder: PerfMonitor binderTransact: time=499ms interface=vendor.mediatek.hardware.apuware.apusys.INeuronApusys code=23
E apusys  : memMapDeviceVa: map mem(N/0) fail(Out of memory)
E apusys  : memAlloc: map device va fail(37765120/256/0/0x1)
E apuware_server: session_memAlloc failed ... size:37765120 align:256 mem_type:0 mem_flag:1
E apuware_hidl: mmap failed sharedFd=0, size=37765120: No such device
E apuware_hidl: session_memFree key not find in m_dupFdMap  [×~40, one per NeuroPilot signature tensor]
E LiteRtDietChat: Failed to create engine: INTERNAL: ERROR: [llm_litert_npu_compiled_model_executor.cc:2589]
```

### All Measured Data Points (6 Attempts)

| Attempt | prefill | cache | APUSys allocation | Pool limit | Result |
|---------|---------|-------|-------------------|------------|--------|
| 1 | 128 | 128 | 18,890,752 (~18MB) | ~16MB | FAIL — closest |
| 2 | 32  | 64  | 37,765,120 (~36MB) | ~16MB | FAIL |
| 3 | 32  | 256 | 37,765,120 (~36MB) | ~16MB | FAIL (same — cache doesn't matter at low prefill) |
| 4 | 128 | 64  | 37,765,120 (~36MB) | ~16MB | FAIL |
| 5 | 64  | 64  | 37,765,120 (~36MB) | ~16MB | FAIL |
| 6 | 256 | 256 | 41,680,896 (~40MB) | ~16MB | FAIL — LARGER than expected |
| 7 (Gemma 3 1B) | 128 | 128 | 33,914,880 (~33.9MB) | ~16MB | FAIL — smaller model, same pool problem |

**Conclusion (2026-05-09): No viable configuration found.** The minimum allocation observed is 18.9MB (Attempt 1 / Gemma 4 2B prefill_128), which is ~3MB above the ~16MB pool limit. Switching to a smaller model (Gemma 3 1B) makes things worse: 33.9MB at the same prefill_128, likely due to architecture differences in DLA graph topology.

The formula `2,415,919,104 / prefill_length + 16,384` (derived after Attempt 5) was **wrong** — it predicted ~9.4MB for prefill_256 but actual was ~40MB. Attempt 6 invalidated it.

**Empirical pattern:**
- `prefill=cache=128` → 18.9MB (special case, smallest)
- `prefill=cache=256` → 40MB (larger)
- All other tested combos → 36–38MB

No clean formula fits all 6 data points. The DLA compiler's internal buffer layout is more complex than a simple prefill/cache function.

### What Is NOT The Issue

- Physical RAM: 357MB CMA free (not a RAM shortage)
- Stale allocations from crashed sessions: rebooting didn't help
- MIUI AI processes taking the pool: killing them didn't help
- NeuroPilot SDK version (v8_0_10 is correct for NeuroPilot 6.0.3)
- cache_length argument propagation: verified KV tensor shapes differ between exports; the DLA compiler doesn't reduce allocation based on cache size

### Model Architecture (Gemma 4 E2B)

From `config.json`:
- `num_hidden_layers`: 35, `num_kv_shared_layers`: 20 → 15 unique KV cache layers
- `num_key_value_heads`: 1 (GQA), `num_attention_heads`: 8
- `head_dim`: 256 (sliding attention), `global_head_dim`: 512 (full attention at layers 4, 9, 14)
- `sliding_window`: 512, `hidden_size`: 1536

KV cache tensors in the generic TFLite (cache_length=64, prefill_32):
- Most layers: `[1, 1, 64, 256]` — very small (32KB)
- Full attention layers 4,9,14: `[1, 1, 64, 512]` — 64KB
- Total KV input tensors: ~1.18MB — this is NOT what the DLA buffer is for

### JNI / Signatures Note

The JNI library (`liblitertlm_jni.so`) hardcodes which prefill signature to look for:
- Original JNI (committed in git): `prefill_128` hardcoded → must use with `--prefill-lengths 128` exports
- Patched JNI (worktree, uncommitted): `prefill_32` hardcoded → must use with `--prefill-lengths 32` exports

Currently: app is installed with the **original (prefill_128) JNI**, waiting for the `cache_64, prefill_128` model.

---

## Export Pipeline

### GCP VM

`litert-export-spot` in `europe-west1-b`.

Script: `~/src/litert-build/tools/build_mt6985_gemma3_litertlm.py`  
VEnv: `~/src/litert-build/.venv-litert`  
Output: `~/src/litert-build/tmp/gemma4-mt6985-build-{CL}-stable/`

### Build Command

```bash
cd ~/src/litert-build
source .venv-litert/bin/activate
# Attempt 4 (current best): prefill_128 to avoid 37MB DLA floor from prefill_32
python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-4-e2b-it \
  --model-family gemma4 \
  --cache-length 64 \
  --prefill-lengths 128 \
  --output-dir /home/ae/src/litert-build/tmp/gemma4-mt6985-build-64-p128 \
  --keep-intermediates
```

### Export Stages (sequential, total ~6-8h)

1. **Weight loading** (~5min): loads 2B weights from HuggingFace cache
2. **Export prefill/decode model to MLIR** (~1h): MLIR lowering for prefill_32 + decode signatures
3. **Run LiteRT Converter Passes + DLA compile** (~3-4h): the big step — turns MLIR into MT6985 DLA bytecode
4. **Export auxiliary models** (embedder, per_layer_embedder, tokenizer) (~1h)
5. **Quantize + DLA compile auxiliary models** (~10min): INT4/INT8 quantization + NeuroPilot compile
6. **Package into .litertlm** (~10min): bundles all compiled TFLite + metadata

### Key Files After Export

```
gemma4-mt6985-build-256-stable/
  compiled_mt6985/
    prefill_decode.tflite           (~1.1GB, DLA-compiled INT4)
    embedder.tflite                 (~198MB)
    per_layer_embedder.tflite       (~2.2GB)
    prefill_decode_weight_only_wi4_afp32.tflite  (INT4 weights)
    embedder_weight_only_wi4_afp32.tflite
    per_layer_embedder_weight_only_wi8_afp32.tflite
  export_work/
    model.tflite                    (~9GB, raw float prefill/decode)
    per_layer_embedder.tflite       (~8.8GB)
    embedder.tflite                 (~1.6GB)
    llm_metadata.pb
    tokenizer.json
  gemma-4-e2b-it_mt6985.litertlm   (~3.3GB, final bundle)
```

---

## Device Deployment

### Push Model to Device

```bash
# Push to sdcard
adb -s 192.168.0.5:38047 push /tmp/model.litertlm /sdcard/Download/gemma-4-E2B-it.litertlm

# Copy to app private storage (avoids scoped storage restriction)
adb -s 192.168.0.5:38047 shell \
  "cat /sdcard/Download/gemma-4-E2B-it.litertlm | \
   run-as com.dreef3.weightlossapp.debug sh -c \
   'cat > /data/user/0/com.dreef3.weightlossapp.debug/cache/models/gemma-4-E2B-it.litertlm'"
```

### Run NPU Smoke Test

There are two smoke test intents in `MainActivity`:

- `runCoachNpuSmokeTest` — uses `selectedCoachNpuSmokeTestEngine` (the old hardcoded engine, does NOT use the user-selected model)
- `runSelectedCoachSmokeTest` — uses `container.dietChatEngine` (the SELECTED model, i.e. whichever model is saved in DataStore `coach_model` key)
- `runSelectedCoachDoubleSmokeTest` — same as above but runs cold + warm (two inferences)

**Always use `runSelectedCoachSmokeTest` to test whichever model is currently active.** The `runCoachNpuSmokeTest` intent does not reflect the active model selection.

```bash
ADB="adb -s 100.104.183.118:5555"
PKG="com.dreef3.weightlossapp.debug"
MAIN="$PKG/com.dreef3.weightlossapp.app.MainActivity"

# Select model in DataStore (one-time, persists across restarts):
$ADB shell am start -n $MAIN --es setCoachModelStorageKey gemma3_mt6985

# Run smoke test on the selected model:
$ADB shell am start -n $MAIN --ez runSelectedCoachSmokeTest true

# Watch for result (from MainActivity tag):
APP_PID=$($ADB shell pidof $PKG | tr -d '\r')
$ADB logcat --pid=$APP_PID | grep -E "smoke test|failed|succeeded"
```

**Success:** `I MainActivity: Selected coach smoke test succeeded [cold] in Xms: OK`  
**Failure:** `E MainActivity: Selected coach smoke test failed [cold] after Xms` + underlying error in logcat

---

## Attempts Log

### Attempt 1 — prefill_128, cache_128 (original model, 2026-05-06)

Model on device as `gemma-4-E2B-it.litertlm` (3.3GB).  
Signature: `prefill_128`. Built with: `--cache-length 128 --prefill-lengths 128`  
Result: `APUSys failed to allocate unique shared dram buffer: size=18890752` (~18MB, 2MB over limit)

### Attempt 2 — prefill_32, cache_64 (2026-05-07 ~13:00 UTC)

Hypothesis (wrong): halving cache_length would halve the allocation to ~9MB.  
Build: `--cache-length 64 --prefill-lengths 32`  
Result: 37,765,120 bytes (~37MB) — 2× WORSE than expected.  
Key learning: `prefill_32` models allocate 37MB regardless of cache_length.  
Export took ~7h total (second run after disk-full failure on first run).

### Attempt 3 — prefill_32, cache_256 (2026-05-07 ~21:30 UTC to 2026-05-08 ~06:00 UTC)

Hypothesis (wrong): inverse formula predicted 9.4MB.  
Build: `--cache-length 256 --prefill-lengths 32`  
Result: 37,765,120 bytes (~37MB) — identical to cache_64!  
Key learning: `prefill_32` always gives 37MB; `prefill_lengths` is the critical variable, not `cache_length`.

### Attempt 4 — prefill_128, cache_64 (2026-05-07 ~22:11 UTC, completed 2026-05-08)

Root cause identified: `prefill_32` causes DLA to use 256-slot buffer floor.  
Hypothesis (wrong): `prefill_128` + `cache_64` should give ~9MB by scaling linearly from 18MB.  
Build: `--cache-length 64 --prefill-lengths 128`  
Artifact: `/home/ae/gemma4-mt6985-p128-c64.litertlm` (3528327168 bytes)  
JNI: restored original (prefill_128) from git HEAD, app installed.  
Result: `APUSys failed to allocate unique shared dram buffer: size=37765120` — still 37MB!

**Progress vs. Attempt 3:** auxiliary submodels now restore from compiled network successfully (5 of them). The main PREFILL_DECODE submodel is the one hitting APUSys OOM.

### Attempt 5 — prefill_64, cache_64 (2026-05-08, completed 2026-05-09)

Hypothesis (wrong): `prefill_64 = cache_64` would give ~9.4MB (formula computed in elements, not bytes).  
Build: `--cache-length 64 --prefill-lengths 64`  
Artifact: `gemma-4-E2B-it.litertlm` deployed to device (3,735,943,020 bytes)  
JNI: rebuilt on GCP VM with `prefill_64` constants, installed in npu-rebase worktree APK.  
Result: `apusys: memMapDeviceVa: map mem fail(Out of memory)` for `size=37765120` — identical to Attempt 4!

**Root cause confirmed**: APUSys device-VA OOM. Kernel log shows binder call to `INeuronApusys` (499ms), then `memAlloc: map device va fail(37765120/256/0/0x1)`, then 40+ `session_memFree key not find` cleanup errors.  
**Formula corrected**: allocation = `2,415,919,104 / prefill_length + 16,384` bytes. For prefill_64: 37.75MB. Cache length is irrelevant. Requires **prefill_256** to get below 16MB (~9.4MB predicted).

---

## SDK / Library Versions

| Component | Version | Notes |
|-----------|---------|-------|
| NeuroPilot on device | 6.0.3 | `libneuron_runtime_vpp.6.0.3.so` |
| NeuroPilot SDK (build) | v8_0_10 | Correct match for 6.0.3 |
| LiteRT-LM JNI | custom | Patched with prefill_32; in `jniLibs/arm64-v8a/` |
| MT6985 DLA | MT6985 backend | In `compiled_mt6985/` TFLite bytecode |

**v8_0_8**: fails (INT4 DLA format mismatch)  
**v9_0_3**: fails (DLA format incompatible)  
**v8_0_10**: correct

---

---

## Gemma 3 270M on MT6985 — New Investigation (2026-05-09)

### Background

After closing the Gemma 4 E2B investigation (all 7 configurations failed with APUSys OOM), pivoted to Gemma 3 270M — a much smaller model that should require a smaller DLA working buffer.

Model: `google/gemma-3-270m-it`, exported with `--prefill-lengths 128 --cache-length 128`.  
File: `gemma-3-270m-it_mt6985.litertlm` (~242MB)

### Build Fix Applied on GCP (2026-05-09)

**Problem**: `apply_plugin failed` / DLA build failed with `Bias should be floating point type` (992 instances).  
**Root cause**: Exporting with `dynamic_wi4_afp32` at the litert-torch stage produced INT32 biases. The compile-time `weight_only_wi4_afp32` pass saw the model as "already partially quantized" (1.00x compression ratio) and left INT32 biases unchanged. DLA requires FP32 biases.  
**Fix**: Patched `build_mt6985_gemma3_litertlm.py` line 936:
```python
# Before:
quantization_recipe=args.quantization_recipe,
# After (v2 build):
quantization_recipe=None if args.model_family == "gemma3" else args.quantization_recipe,
```
This skips export-time quantization for gemma3, exporting float32. Compile-time `weight_only_wi4_afp32` then correctly produces INT4 weights + FP32 biases (7.5× compression vs 1.0× before).

### APUSys DRAM — PASSED (first ever success, 2026-05-09)

Smoke test run with `setCoachModelStorageKey=gemma3_mt6985`, `runCoachNpuSmokeTest=true`.

Result: **251 compiled network partitions restored successfully from DLA bytecode** — no APUSys OOM.  
All partition `NeuronModel_restoreFromCompiledNetwork` calls succeeded.

This confirms Gemma 3 270M (prefill_128, cache_128) fits within the ~16MB APUSys unique shared DRAM pool on this MIUI MT6985 device.

### Inference Failure — Tensor Strides Not Supported (2026-05-09)

After loading succeeded, the warmup inference fails:

```
E/litert: [dispatch_api.cc:271] Failed to register tensor buffer: Tensor strides are not supported
E/litert: [dispatch_delegate_kernel.cc:167] ERROR: dispatch_delegate_kernel.cc:330
E/tflite: Node number 243 (DELEGATE) failed to invoke.
E/litert: [litert_compiled_model.cc:162] Failed to invoke
E/LiteRtDietChat: Failed to create engine: INTERNAL: result: Inference warmup run for Gemma3 (prefill) failed.Failed to invoke the compiled model
```

**Root cause (v2 model — initial diagnosis)**: The `TF_LITE_EMBEDDER` section had `backend_constraint: npu`, routing it through `DispatchDelegate`. The NPU dispatch API at `dispatch_api.cc:271` hard-rejects tensors with strides. Embedding GATHER produces strided tensor views → dispatch fails.

### Fix Attempt — CPU Embedder (v3, 2026-05-09)

`tools/repack_gemma3_270m_cpu_embedder.sh` repackages from v2 build intermediates:
- `embedder_weight_only_wi4_afp32.tflite` → `backend_constraint="cpu"` (no DLA)
- `prefill_decode.tflite` → `backend_constraint="npu"` (DLA, unchanged)
- `export_work/auxiliary.tflite` → no constraint

**Same strides error, now in the PREFILL model (v3, 2026-05-09):**

```
E/litert: [dispatch_api.cc:271] Failed to register tensor buffer: Tensor strides are not supported
E/litert: [dispatch_delegate_kernel.cc:167] ERROR: dispatch_delegate_kernel.cc:330
E/tflite: Node number 243 (DELEGATE) failed to invoke.
E/litert: [litert_compiled_model.cc:162] Failed to invoke
E/LiteRtDietChat: Failed to create engine: INTERNAL: Inference warmup run for Gemma3 (prefill) failed.
```

**Updated root cause**: The strides error is NOT exclusive to the embedder. The embedding GATHER (even running on CPU) produces a strided output tensor. This strided tensor becomes the INPUT to the first DLA partition in `TF_LITE_PREFILL_DECODE` (node 243). The MediaTek dispatch API rejects it at the same `dispatch_api.cc:271` boundary.

XNNPack applied to 2/6 nodes in the embedder's subgraphs immediately before the DLA prefill invocation fails, confirming the CPU embedder → DLA prefill boundary is where the striding occurs.

**Key finding**: "Tensor strides are not supported" is a fundamental MediaTek dispatch API limitation at ANY CPU→DLA tensor handoff where the CPU side produces a non-contiguous (strided) memory layout. This is NOT model-specific — it's a property of the DLA dispatch layer.

### Next Test — CPU-Only (v4, 2026-05-09)

`gemma-3-270m-it_mt6985_cpu_only.litertlm` (238.9 MB) packages:
- `embedder_weight_only_wi4_afp32.tflite` → `backend_constraint="cpu"`
- `prefill_decode_weight_only_wi4_afp32.tflite` → `backend_constraint="cpu"` (INT4 weights, no DLA)
- `export_work/auxiliary.tflite` → no constraint

This confirms whether inference is correct end-to-end (without DLA performance). If this passes, the model and litertlm packaging are correct; the blocker is purely the DLA strides limitation. Inference will be very slow (~minutes/token) but validates the stack.

To fix DLA inference, the CPU→DLA boundary needs contiguous tensors. Options:
1. Modify the embedder TFLite to insert a densifying PACK/COPY op after GATHER
2. Find a LiteRT-LM engine config that forces a copy at submodel boundaries
3. Compile the prefill model to include the embedding lookup internally (no separate embedder handoff)

### Fix Attempt — Densified Embedder (v5, 2026-05-10)

`tools/densify_embedder_output.py` inserts `ADD(embed_out, scalar_zero) → new_output` after each subgraph's final tensor, forcing XNNPack to materialise a fresh contiguous buffer.

**v5 error: "Failed to load model from buffer" — NPU accelerator rejected non-DLA model**

The v5 litertlm packaged the densified embedder with `backend_constraint="npu"`, but the densified model is XNNPack-only (not DLA-compiled). LiteRT's NPU auto-registration failed:
```
W litert: [auto_registration.cc:78] NPU accelerator could not be loaded and registered: kLiteRtStatusErrorInvalidArgument.
...
Failed to create engine: INVALID_ARGUMENT: ERROR: [llm_litert_npu_compiled_model_executor.cc:2881]
└ Failed to load model from buffer
```

### Fix Attempt — Densified CPU Embedder (v6, 2026-05-10)

Rebuilt v6 with `backend_constraint="cpu"` for the densified embedder. Same "Failed to load model from buffer" error persisted.

**v6 bug 1: Missing TFL3 file identifier**

`flatbuffers.Builder.Finish()` on this version (25.12.19) does not write the 4-byte TFLite identifier "TFL3" at bytes 4-7. TFLite validates this identifier at load time. Fix: manually increment root_offset by 4 and prepend `b'TFL3'`.

**v6 bug 2: External buffer data stripped**

The original `embedder_weight_only_wi4_afp32.tflite` is **90 MB** — the 80 MB embedding matrix is stored as an external buffer (TFLite extended format: `Buffer.offset=6297104, Buffer.size=83886080`). The `InitFromPackedBuf` + `Pack` roundtrip only wrote the 6.3 MB flatbuffer header, omitting the weight data. Result: "Constant buffer 4 specified an out of range offset."

Fix: inline external buffers before re-serializing (`buf.data = list(file_bytes[offset:offset+size])`). Output is now 90.2 MB and validates with the TFLite Python interpreter.

**Fixed v6 (2026-05-10):** deployed 241 MB litertlm (139 MB DLA prefill_decode + 90 MB densified CPU embedder + 70 KB aux). Smoke test: **FAIL — same `dispatch_api.cc:271` strides error as v3**.

```
E/litert: [dispatch_api.cc:271] Failed to register tensor buffer: Tensor strides are not supported
```

**v6 conclusion**: `ADD(embed_out, scalar_zero)` does not produce a contiguous output. Probable cause: XNNPack detects `ADD(x, 0)` as a no-op and aliases the ADD output tensor directly to the strided embedding lookup output — no materialisation occurs. The DLA dispatch API still receives the strided memory reference at the `embeddings` tensor boundary.

### Fix Attempt — Strides Removal via Binary Patch (2026-05-10)

After v6 confirmed model-level densification cannot bypass the strides rejection, the fix moved to the dispatch library directly.

**Root cause in dispatch library**: `litert_dispatch_device_context.cc:RegisterTensorBuffer` had an explicit check:
```cpp
if (tensor_type.layout.has_strides) {
  return Error(..., "Tensor strides are not supported");
}
```
This rejects any tensor with a non-contiguous layout — including the strided GATHER output at the CPU→DLA boundary.

**Fix**: Binary-patched the `libLiteRtDispatch_MediaTek.so` in the worktree's jniLibs to NOP the strides rejection branch. App reinstalled with patched library.

**Result after strides NOP**: New failure — APUSys DRAM OOM at decode warmup:
```
E apusys: memAlloc: map device va fail(4980736/256/0/0x1)
```
The ~5MB decode buffer fails to import. Prefill succeeds (251 partitions restored, partition-level warmup runs), but decode cannot allocate.

### Root Cause Analysis — APUSys DRAM Pool Exhaustion During Decode (2026-05-10)

~125 prefill DLA partitions each create a `NeuronExecution`. `AttachInput`/`AttachOutput` call `NeuronExecution_setInputFromMemory` / `NeuronExecution_setOutputFromMemory` immediately, importing tensor buffers into the APUSys device-VA DRAM pool. These imports persist for the lifetime of the `NeuronExecution` object.

`DetachInput`/`DetachOutput` in the MediaTek dispatch implementation are no-ops — they do not release APUSys imports. After all ~125 prefill partitions' `AttachInput`/`AttachOutput` calls complete, the entire ~16MB APUSys pool is consumed. When decode's DispatchDelegate then tries to `RegisterTensorBuffer` + `AttachInput` for its ~5MB buffer, the pool returns OOM.

**Key insight**: APUSys imports held per `NeuronExecution` are released only when `NeuronExecution_free` is called — not at `execution_compute` completion, not at `DetachInput`/`DetachOutput`.

### Fix Attempt — Lazy Import + Execution Recreation (v7, 2026-05-11)

Source rebuild of `libLiteRtDispatch_MediaTek.so` from `src/LiteRT-LM` (LiteRT commit `472d1c0f`), Bazel workspace `042ed1f5`.

**Three changes in source:**

1. **Strides check removed** from `litert_dispatch_device_context.cc:RegisterTensorBuffer` — source equivalent of the binary NOP.

2. **Lazy-import pattern** in `litert_dispatch_invocation_context.cc/.h`:
   - `AttachInput`/`AttachOutput` push `(index, handle)` pairs into `pending_inputs_`/`pending_outputs_` member vectors instead of calling APUSys immediately.
   - `Invoke()` iterates these vectors, calls `execution_set_input/output_from_memory` (APUSys import happens here, right before compute), calls `execution_compute`, then immediately frees the `NeuronExecution` and recreates it — releasing all APUSys DRAM imports after each partition's compute completes.
   - Pending vectors persist across `Invoke()` calls (DispatchDelegateKernel only calls `AttachInput`/`AttachOutput` on the first `Eval()`, then sets `attached=true`).

3. **`LiteRtDispatchInitializeT` 2-arg signature** — LiteRT v2.1.4 runtime calls with `(LiteRtEnvironment, LiteRtOptions)`; the `576fe2d1` LiteRT commit changed this to a 3-arg form `(const LiteRtRuntimeContext*, LiteRtEnvironment, LiteRtOptions)`. The `472d1c0f` commit already uses 2-arg; ensured consistency in the cached headers.

**Build result**: 197 Bazel actions. Output: 1.7 MB unstripped, stripped to 293 KB with `llvm-strip --strip-unneeded`.

**Status (2026-05-11)**: Library deployed to worktree jniLibs. Debug APK built (`assembleDebug` succeeded, 73 tasks). Device currently unreachable — awaiting reconnection to run smoke test.

---

## Next Steps

### Attempt 6 — prefill_256, cache_256 (recommended — pre-v7 analysis, now superseded)

**Predicted allocation**: `2,415,919,104 / 256 + 16,384 = 9,453,568 bytes (~9.4MB)` → fits in ~16MB pool.

Required changes:
1. **Rebuild JNI** with `prefill_256` constants (`kPrefillSignature = "prefill_256"`, `kPrefillSize = 256`, etc.)  
   Source: `/home/ae/src/LiteRT-LM/runtime/executor/llm_litert_npu_compiled_model_executor.cc`  
   Build via: `./tools/gcp/run_jni_build_on_vm.sh` after patching the executor
2. **Re-export on GCP** with `--prefill-lengths 256 --cache-length 256`
3. Build APK, install, push model, run smoke test

**Risk**: This is the only predicted configuration that fits. If the formula's inverse relationship breaks at larger prefill sizes, or if there's a different floor, this may also fail.

**Alternative**: **prefill_192, cache_192** → ~12.6MB → slightly smaller margin but avoids potential issues at powers of 2.

### ADB model deployment (learned from Attempts 2-4)

**Always use `adb push` — never pipe large binary files via `cat | adb shell`.**

Correct workflow:
```bash
# 1. Push to /data/local/tmp (world-writable, readable by run-as)
adb -s 100.104.183.118:5555 push /path/to/model.litertlm /data/local/tmp/model.litertlm

# 2. On-device copy to app storage
adb -s 100.104.183.118:5555 shell \
  "run-as com.dreef3.weightlossapp.debug cp /data/local/tmp/model.litertlm \
   /data/user/0/com.dreef3.weightlossapp.debug/cache/models/gemma-4-E2B-it.litertlm"

# 3. Cleanup
adb -s 100.104.183.118:5555 shell rm /data/local/tmp/model.litertlm
```

Note: `/sdcard/Download/` push fails because `run-as` can't read FUSE-owned files; use `/data/local/tmp/` instead.

## Export Command Reference

```bash
# Attempt 6 (next): prefill_256, cache_256
python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-4-e2b-it --model-family gemma4 \
  --cache-length 256 --prefill-lengths 256 \
  --output-dir /home/ae/src/litert-build/tmp/gemma4-mt6985-build-256-p256 \
  --keep-intermediates

# Check export log
tail -f ~/src/litert-build/tmp/gemma4-mt6985-build-256-p256.log
```

Also create `tools/run_nightly_export_cache256_p256.sh` (copy of cache64_p64 variant with updated params).

---

## Fix Attempt — Rebuild Dispatch With resolve_symbols_in_exec=false (v9, 2026-05-11)

### Root Cause of SIGABRT (all of v7, v8)

The LiteRT-LM `.bazelrc` (line 44) unconditionally sets `--define=resolve_symbols_in_exec=true`. This causes the `litert_dynamic_lib` Bazel macro to enable `-no_undefined` feature removal, allowing the dispatch plugin to be built with **UND (unresolved) symbols**:

- `NeuronAdapterApi::Create` — left as UND import, expected from parent binary
- `LiteRt*` functions — left as UND imports, expected from parent binary

When `LiteRtDispatchInitialize` dlopen's the plugin with `RTLD_NOW | RTLD_LOCAL`, `RTLD_NOW` demands all symbols resolve immediately. `NeuronAdapterApi::Create` is NOT exported by `liblitertlm_jni.so` → dlopen fails → `LiteRtDispatchInitialize` returns error → `has_dispatch_runtime_=false` → `CreateDelegateKernelInterface()` calls `LITERT_FATAL` → SIGABRT at `+316`.

**Crash chain:**
```
dlopen("libLiteRtDispatch_MediaTek.so", RTLD_NOW|RTLD_LOCAL) → FAIL (unresolved NeuronAdapterApi::Create)
→ SharedLibrary::Load returns error
→ LiteRtDispatchInitialize returns kLiteRtStatusErrorRuntimeFailure  
→ InitializeDispatchApi() fails
→ Initialize() sets has_dispatch_runtime_ = false
→ CreateDelegateKernelInterface() hits LITERT_FATAL("No usable Dispatch runtime found")
→ abort() → SIGABRT at DispatchDelegate::CreateDelegateKernelInterface()+316
```

### Fix

Rebuild with `--define=resolve_symbols_in_exec=false`, which compiles `neuron_adapter_api.cc` directly into the dispatch plugin:

```bash
cd /home/ae/src/LiteRT-LM
bazelisk build --config=android_arm64 \
  --define=resolve_symbols_in_exec=false \
  @litert//litert/vendors/mediatek/dispatch:dispatch_api_so
```

**Result (v9):** 611KB binary (vs 239KB for v7/v8 which lacked the neuron_adapter_api code).

- No `NeuronAdapterApi::Create` as UND in `.dynsym` ✓
- `libneuronusdk_adapter.mtk.so` string present (LoadSymbols compiled in) ✓
- Remaining UND `LiteRt*@VERS_1.0` symbols resolved by `libLiteRt.so` already in jniLibs ✓

### Deploy

Binary copied to `.worktrees/npu-rebase/android/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_MediaTek.so`.

## Current Status (2026-05-11)

### Active: v9 dispatch library smoke test

Debug APK with v9 `libLiteRtDispatch_MediaTek.so` (resolve_symbols_in_exec=false + lazy-import + strides removed) is ready. Device offline.

When device reconnects:
```bash
ADB=/home/ae/android-sdk/platform-tools/adb
$ADB connect 100.104.183.118:5555
cd /home/ae/weight-loss-app/.worktrees/npu-rebase/android && ./gradlew installDebug

PKG="com.dreef3.weightlossapp.debug"
MAIN="$PKG/com.dreef3.weightlossapp.app.MainActivity"
$ADB -s 100.104.183.118:5555 shell am start -n $MAIN --es setCoachModelStorageKey gemma3_mt6985
$ADB -s 100.104.183.118:5555 shell am start -n $MAIN --ez runSelectedCoachSmokeTest true
APP_PID=$($ADB -s 100.104.183.118:5555 shell pidof $PKG | tr -d '\r')
$ADB -s 100.104.183.118:5555 logcat --pid=$APP_PID | grep -E "smoke test|failed|succeeded|SIGABRT|Fatal"
```

**Expected success**: `Selected coach smoke test succeeded [cold] in Xms: OK`  
**If SIGABRT still occurs**: Check logcat for which UND symbol failed; verify `libLiteRt.so` version in jniLibs exports versioned symbols.  
**If decode OOM persists**: APUSys lazy-import fix (v7) wasn't reached before; now it can be evaluated.  
**If a different error appears**: Check logcat for the new failure mode.
