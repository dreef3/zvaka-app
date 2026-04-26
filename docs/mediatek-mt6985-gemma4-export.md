# MediaTek MT6985 Gemma 4 Export Notes

This document captures the export, AOT compile, and runtime findings from the
Gemma 4 E2B LiteRT-LM work for the MediaTek `MT6985` path.

Use this as the starting point if we need to repeat the same work for another
MediaTek SoC.

Preserved handoff index:

- `docs/mediatek-mt6985-npu-retrospective.md`

Preserved external source branches:

- `LiteRT-LM`: `https://github.com/dreef3/LiteRT-LM/tree/dreef3/mt6985-npu-preservation`
  - commit `4cff23c144c28ca6497502cde42ebf0f2c3de7b3`
- `litert-torch`: `https://github.com/dreef3/litert-torch/tree/dreef3/mt6985-gemma4-export-fixes`
  - commit `532a12472b1e15e08ead61a3eb8f718b002dbab4`
- `LiteRT`: `https://github.com/dreef3/LiteRT/tree/dreef3/mt6985-dispatch-diagnostics`
  - commit `a2e573b7f5134140605fc91e488fb8e90e3729a0`

## Goal

Build a `.litertlm` bundle that keeps the heavy Gemma 4 text path on the
MediaTek NPU while preserving the app's single backend selector UX.

Target model:

- `google/gemma-4-e2b-it`

Current practical bundle shape:

- `PREFILL_DECODE`: NPU compiled
- `EMBEDDER`: NPU compiled
- `PER_LAYER_EMBEDDER`: NPU compiled
- `AUX`: raw TFLite model, intended for CPU LiteRT execution in the native
  executor

## High-Level Findings

1. Native/runtime loading was not the long-term blocker.
   The main problems moved to model export shape and MediaTek AOT compilation.

2. Generic Gemma 4 export does not work unchanged for the MediaTek compiled NPU
   executor.
   We had to patch both `litert-torch` export behavior and the packaging flow.

3. `Gemma4` on this host required `float32` export load, not `bfloat16`, for
   quantization/AOT compatibility.
   `bfloat16` avoided RAM pressure, but leaked unsupported types into AOT.

4. Split cache was required for the text model path.
   The non-split cache export repeatedly failed in MediaTek `apply_plugin` with
   reshape/input-shape issues near cache updates.

5. AUX still does not compile on this WSL host.
   After fixing the aux cache-update export bug, the remaining AUX compile
   failure is tied to MVPU/OpenCL-targeted toolchain requirements on the host,
   not the original cache-update graph bug.

## Environment Findings

### WSL2 host

- Host had to be used directly instead of Docker to avoid the tight container
  memory cap.
- `Python 3.12` worked better than `3.10` due upstream dependencies.
- `uv` was used for environment management.
- WSL memory cap was `12 GB`; swap had to be increased significantly to get
  through export reliably.

### Runtime / toolchain version alignment

Android-side published artifacts currently top out at:

- `com.google.ai.edge.litert:litert:2.1.4`
- `com.google.ai.edge.litertlm:litertlm-android:0.10.2`

The earlier WSL export/AOT environment was using a newer nightly line:

- `ai-edge-litert-nightly 2.2.0.dev...`
- `ai-edge-quantizer-nightly`

This became the strongest root-cause candidate once the app, JNI packaging,
model selection, and runtime branch issues were fixed but the device still
failed at `Failed to create context from context binary: Failed to finish compilation`.

Important stable Python line discovered on PyPI:

- `ai-edge-litert==2.1.4`
- `ai-edge-quantizer==0.6.0`

`ai-edge-quantizer 0.6.0` explicitly depends on `ai-edge-litert==2.1.4`.

This means the only clean alignment direction currently available is:

- downgrade the host/export/AOT toolchain to `2.1.4`-compatible packages
- not upgrade the Android app runtime beyond `2.1.4`, because no newer Android
  Maven artifacts were available

### Useful swap observation

- `12 GB RAM + 4 GB swap`: not enough
- `12 GB RAM + 20 GB swap`: better but still unstable during heavy stages
- Extra in-guest swap reduced immediate OOM-style failures but did not solve the
  full export/compile path by itself

### GCP spot VM fallback

When the Windows/WSL host becomes unstable, a spot/preemptible GCP VM is a good
fallback for the export step.

Prepared workflow scripts live under:

- `tools/gcp/create_spot_vm.sh`
- `tools/gcp/sync_export_workspace.sh`
- `tools/gcp/run_stable_export_on_vm.sh`
- `tools/gcp/fetch_export_artifact.sh`
- `tools/gcp/delete_spot_vm.sh`

Defaults:

- project: inferred from `~/calorie-drive-upload-api/terraform/terraform.tfvars`
- region: `europe-west1`
- zone: `europe-west1-b`
- machine type: `n4-standard-16`
- boot disk type: let GCE choose a supported default unless overridden

The VM region is intentionally separate from the backend function region. The
backend repo is only used here as a convenient source for the existing GCP
project id and other conventions.

The intended loop is:

1. create a spot VM
2. sync `tools/` and the patched local `litert-torch` checkout
3. bootstrap a stable `2.1.4` export env on the VM
4. run the stable export there
5. fetch the artifact back
6. delete the VM

Working nightly VM path now saved locally:

- bootstrap nightly env on the VM with `tools/bootstrap_litert_export_env.sh`
- run `tools/run_nightly_export_cache128.sh`
- or use `tools/gcp/run_nightly_export_on_vm.sh`
- if MediaTek host SDK compatibility needs probing without changing the graph,
  the builder now supports:
  - `MEDIATEK_SDK_LIB_DIR=/absolute/path/to/host/lib`
  - or `MEDIATEK_SDK_LIB_SUBDIR=v8_0_8/host/lib`

### Colab

- Free Colab memory is likely too small for the full Gemma 4 MLIR/export path.
- A notebook was added, but free Colab should be treated as unlikely to finish
  reliably without a higher-memory runtime.

## Required Source Patches

### `tools/build_mt6985_gemma3_litertlm.py`

Key changes made in the helper:

- force CPU JAX
- load Gemma 4 through a low-friction local path that works on this host
- patch external RoPE export for Gemma 4
- enable a Gemma 4 split-cache export path
- use per-model quantization/compile behavior instead of one recipe everywhere
- package raw AUX instead of trying to force MediaTek AOT on it
- default Gemma 4 to `GEMMA4_DISABLE_RMS_HLFB=1` because `odml.rms_norm`
  `STABLEHLO_COMPOSITE` broke MediaTek compilation
- default Gemma 4 to `GEMMA4_LIGHTWEIGHT_CONVERSION=0` to preserve the known
  successful nightly build path

## Stable 2.1.4 GCP Rerun Results

Fresh reruns on a clean `europe-west1-b` spot VM established a more precise
stable-toolchain blocker.

- the stable `2.1.4` path can fully re-export Gemma 4 submodels on the VM
- `prefill_decode` still fails in MediaTek `apply_plugin` during AOT compile
- the raw exported transformer (`export_work/model.tflite`) also fails the same
  way when compiled directly, so this is not only the
  `weight_only_wi4_afp32` quantization step
- the plugin-level failure is `Unsupported operation: EXTENSION`
- disabling Gemma 4 lightweight conversion with
  `GEMMA4_LIGHTWEIGHT_CONVERSION=0` did not remove the `EXTENSION` failure
- no final `.litertlm` bundle is produced on the stable VM path yet

Current interpretation:

- the stable `2.1.4` MediaTek compiler is rejecting an op form present in the
  Gemma 4 transformer export graph
- the blocker is now narrowed beyond general VM stability, package alignment, or
  the specific `weight_only_wi4_afp32` recipe

## Nightly Build Result

After removing Gemma 4 RMSNorm HLFB composites, the nightly host compiler could
compile the text-path models again on the GCP VM.

Confirmed nightly host result:

- `prefill_decode` compiled with `weight_only_wi4_afp32`
- `embedder` compiled with `weight_only_wi4_afp32`
- `per_layer_embedder` compiled with `weight_only_wi8_afp32`
- `auxiliary` remained raw TFLite for CPU execution in the patched runtime
- packaged bundle path on VM:
  - `/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512-nightly-nohlfb/gemma-4-e2b-it_mt6985.litertlm`
- fetched local artifact path:
  - `/home/ae/src/model-artifacts/gemma4-mt6985-nightly-nohlfb.litertlm`

Current limitation of that nightly bundle on device:

- the app loads the bundle and reaches MediaTek dispatch initialization
- instrumented dispatch logging now shows the full sequence:
  - `Neuron version 8.2.26`
  - `Failed to restore model from compiled network: status=4 size=27106`
  - `Falling back to DLA bytecode restore path: size=27106 inputs=3 outputs=3`
  - `Failed to create context from context binary: Failed to finish compilation`
- `status=4` is `NEURON_BAD_DATA` from `NeuronAdapter.h`, whose docs say the
  compiled network restore failed because the bytecode version does not match or
  the data is corrupted
- the app already ships the same newer `libLiteRt.so` and
  `libLiteRtDispatch_MediaTek.so` that were built from the `LiteRT-LM` Bazel
  workspace, so replacing those runtime libs did not offer a new path
- the remaining suspicion is MediaTek compiled-bytecode compatibility between the
  host SDK/compiler line and the device-side Neuron runtime

Most likely next experiment:

- rebuild the same no-HLFB nightly bundle with a different MediaTek host SDK lib
  directory override, starting with `MEDIATEK_SDK_LIB_SUBDIR=v8_0_8/host/lib`
  instead of the default `v8_0_10/host/lib`
- this is now a small saved change in the builder, not a one-off manual patch

### `litert_torch/backend/inline_consts.py`

Needed fixes:

- support `torch.bfloat16` constant lowering through `ml_dtypes`
- support BF16 resource-constant handling

This was necessary to keep the export pipeline moving before switching back to
`float32` for the final AOT-friendly path.

### `litert_torch/generative/export_hf/model_ext/gemma4/exportable_module.py`

Needed fixes:

- prune unused multimodal modules during export
- patch prefill/decode position handling for compiler-friendlier graphs

### `litert_torch/generative/export_hf/core/split_cache/exportable_module.py`

Critical aux fix:

- `CacheUpdate` was incorrectly building generic cache objects instead of split
  caches, which reintroduced the old `dynamic_update_slice` path into AUX
  export.
- after fixing that, AUX export completed successfully

## Quantization / Compile Results

The compileable recipe mix on this host was not uniform.

### `PREFILL_DECODE`

Working:

- `weight_only_wi4_afp32`
- `weight_only_wi8_afp32`

Failing or inferior for this path:

- `dynamic_wi4_afp32`
- `dynamic_wi8_afp32`
- generic no-split-cache path

Chosen recipe:

- `weight_only_wi4_afp32`

### `EMBEDDER`

Working:

- raw compile worked
- `weight_only_wi4_afp32` compiled cleanly

Chosen recipe:

- `weight_only_wi4_afp32`

### `PER_LAYER_EMBEDDER`

Working:

- `weight_only_wi8_afp32`
- `dynamic_wi8_afp32`

Failing:

- `weight_only_wi4_afp32`
- `dynamic_wi4_afp32`

Chosen recipe:

- `weight_only_wi8_afp32`

### `AUX`

Status:

- export succeeds after the split-cache `CacheUpdate` fix
- MediaTek AOT still fails on this host

Observed compiler signals:

- missing `libmvpuop_mtk_nn.so`
- missing `libmvpuop_mtk_cv.so`
- OpenCL initialization failure
- `NoExecPlan`

Interpretation:

- this is no longer the old cache-update graph bug
- on this host, AUX wants MVPU/OpenCL-side pieces that are not available from
  the current WSL SDK/toolchain setup

Additional confirmed finding:

- the split-cache `CacheUpdate` exporter in `litert-torch` had a real source bug:
  it instantiated generic cache objects instead of split caches, which
  reintroduced the old cache-update graph into AUX export.
- after fixing that export bug, `auxiliary.tflite` exported cleanly, but MediaTek
  AOT still failed on host-toolchain limitations (`NoExecPlan`, missing MVPU/OpenCL
  pieces).

## Runtime Findings

LiteRT-LM NPU executor requirements:

- `AUX` is mandatory for the NPU executor path
- `PER_LAYER_EMBEDDER` is mandatory for Gemma 4 because the exported text model
  has a `per_layer_embeddings` input
- `PREFILL_DECODE + EMBEDDER` alone is not sufficient

Practical runtime conclusion:

- keep `PREFILL_DECODE`, `EMBEDDER`, and `PER_LAYER_EMBEDDER` on NPU
- run `AUX` on CPU LiteRT compiled-model execution

Confirmed native integration approach:

- `LlmLiteRtNpuCompiledModelExecutor::CreateNpuAuxiliaryContext()` can be patched
  to use `CreateLiteRtCpuOptions(settings)` instead of NPU options.
- this keeps the main transformer path on NPU while moving the tiny AUX model to
  CPU without changing the higher-level app backend selector.
- the NPU executor branch selection for Gemma 4 also needed hardening:
  relying only on `HasPerLayerEmbedder(transformer_model)` was too narrow in our
  mixed packaged flow, so the runtime should also fall back to the presence of a
  `TF_LITE_PER_LAYER_EMBEDDER` model in the bundle when selecting the per-layer
  executor path.

JNI integration note:

- the app does not build LiteRT-LM from source as part of Gradle.
- `android/app/src/main/jniLibs/arm64-v8a/liblitertlm_jni.so` had to be rebuilt
  from the patched `LiteRT-LM` source with Bazel and then copied into the app
  module manually.
- the Bazel build required Git LFS payloads from `LiteRT-LM/prebuilt/android_arm64`
  to be present first.
- the rebuilt JNI library also depends on
  `libGemmaModelConstraintProvider.so`, which is not bundled by the app unless we
  copy it explicitly from `LiteRT-LM/prebuilt/android_arm64/` into
  `android/app/src/main/jniLibs/arm64-v8a/`.

Why this is acceptable:

- AUX is tiny compared with the main transformer path
- the existing executor already uses CPU LiteRT compiled models for embedder
  paths, so CPU AUX is a contained native runtime patch

## Bundle Shape To Preserve

For this host, the most useful bundle is:

- `PREFILL_DECODE`: compiled MT6985 TFLite
- `EMBEDDER`: compiled MT6985 TFLite
- `PER_LAYER_EMBEDDER`: compiled MT6985 TFLite
- `AUX`: raw exported TFLite
- tokenizer + metadata as usual

Do not assume a fully AOT-compiled AUX on another socket unless the host SDK
actually contains the needed MVPU/OpenCL pieces.

## Repeatable Process For Another SoC

1. Set up a native x86 Linux host with enough memory and swap.
2. Use `uv` with `Python 3.12`.
3. Bootstrap the LiteRT export environment.
4. Export Gemma 4 with split cache enabled.
5. Regenerate raw exported submodels under `export_work/`.
6. Probe quantization recipes per model instead of assuming one recipe works for
   all submodels.
7. Expect these probes first:
   - `prefill_decode`
   - `embedder`
   - `per_layer_embedder`
   - `auxiliary`
8. If AUX fails with MVPU/OpenCL messages, treat that as a host/toolchain issue,
   not necessarily a graph issue.
9. Package raw AUX and patch the runtime to run AUX on CPU LiteRT while keeping
   the text path on NPU.
10. Before trusting on-device failures, verify host/device LiteRT versions are
    aligned. If Android artifacts are pinned to `2.1.4`, prefer rebuilding the
    host export/AOT toolchain on the same compatibility line instead of mixing a
    nightly host compiler with a stable device runtime.

## Warnings / Pitfalls

- Do not assume old compiled probe artifacts match the newest raw exports.
  Always compare mtimes or regenerate the compiled artifacts.
- Do not reuse one quantization recipe for all submodels.
- Do not assume `split_cache` is wired correctly for Gemma 4 upstream.
  The stock path required local patching.
- AUX export and AUX compile are separate problems.
  Fixing export did not make AOT compile succeed on this host.

## Current Artifact State

At the time of writing:

- latest raw exports lived under:
  - `/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512/export_work`
- fresh compiled probe artifacts lived under:
  - `/home/ae/src/litert-build/tmp/final-recipe-mix-fresh`
- packaged bundle target path used for the mixed CPU-AUX/NPU-text layout:
  - `/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512/gemma-4-e2b-it_mt6985_cpuaux.litertlm`

Verified working compile mix from the freshest saved exports:

- `model.tflite` -> `weight_only_wi4_afp32`
- `embedder.tflite` -> `weight_only_wi4_afp32`
- `per_layer_embedder.tflite` -> `weight_only_wi8_afp32`
- `auxiliary.tflite` -> raw only on this host

Latest validated local artifact locations:

- raw exported models:
  - `/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512/export_work`
- fresh compiled text-path models:
  - `/home/ae/src/litert-build/tmp/final-recipe-mix-fresh`
- packaged mixed bundle:
  - `/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512/gemma-4-e2b-it_mt6985_cpuaux.litertlm`
- local copied bundle:
  - `/home/ae/src/model-artifacts/gemma4-mt6985-cpuaux.litertlm`

Android app integration notes:

- the app model directory is `Context.cacheDir/models`
- on the debug app, the bundle was sideloaded to:
  - `/data/user/0/com.dreef3.weightlossapp.debug/cache/models/gemma-4-E2B-it.litertlm`
- the sideloaded file name must match `ModelDescriptors.gemma.fileName`
  unless app code is updated
- if a different Gemma 4 bundle is sideloaded under the same file name, clear
  old `gemma-4-E2B-it.litertlm_*mldrift_program_cache.bin` and related vision
  cache files from the app model directory first, otherwise the runtime may try
  to reuse stale compiled caches for a different payload and fail during
  `context binary` creation.

Debug smoke-test procedure:

- clear logcat:
  - `/home/ae/android-sdk/platform-tools/adb -s <device> logcat -c`
- trigger the debug-only coach NPU smoke test from the app itself:
  - `/home/ae/android-sdk/platform-tools/adb -s <device> shell am start -n com.dreef3.weightlossapp.debug/com.dreef3.weightlossapp.app.MainActivity --ez runCoachNpuSmokeTest true`
- check result lines:
  - `/home/ae/android-sdk/platform-tools/adb -s <device> logcat -d | rg "Coach NPU smoke test|sendMessage start|Failed to create context|dispatch_api"`
- expected success signal:
  - `I MainActivity: Coach NPU smoke test succeeded: ...`
- current failure signal from the nightly compiled bundle:
  - `E MainActivity: Coach NPU smoke test failed`
  - `E litert: [dispatch_api.cc:301] Failed to create context from context binary: Failed to finish compilation`

## Next Work If Revisited

If we revisit this for MT6985 or another MediaTek SoC, the next questions are:

1. Can the host SDK/toolchain be fixed so AUX compiles too?
2. If not, is CPU AUX fast enough on device to treat the mixed path as the
   intended design?
3. Can the runtime patch be upstreamed or kept as a local integration layer?
4. If local WSL remains unstable, prefer the GCP spot-VM workflow over repeated
   WSL retries.
