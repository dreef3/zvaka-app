# MediaTek MT6985 NPU Retrospective

This document is the handoff index for the full attempt to make Gemma 4 E2B run
through the MediaTek NPU path on an `MT6985` Android device.

Use this first, then drop into `docs/mediatek-mt6985-gemma4-export.md` for the
submodel export and compiler details.

## Final State

What worked:

- the app was wired to expose a first-class `NPU` backend option for coach flows
- Gemma 4 E2B became selectable by the app's debug smoke-test path
- `litert-torch` was patched enough to export Gemma 4 into a MediaTek-friendly
  split-cache shape
- a nightly host compiler path produced a packaged `.litertlm` bundle with:
  - compiled `PREFILL_DECODE`
  - compiled `EMBEDDER`
  - compiled `PER_LAYER_EMBEDDER`
  - raw `AUX` intended for CPU LiteRT execution
- the Android app loaded that bundle and reached MediaTek dispatch initialization

What still failed:

- the device rejected the embedded MediaTek compiled-network blob before
  execution started
- instrumented runtime logs showed:
  - `Neuron version 8.2.26`
  - `Failed to restore model from compiled network: status=4 size=27106`
  - `status=4 == NEURON_BAD_DATA`
  - fallback DLA-bytecode restore then failed at `compilation_finish`

Best current interpretation:

- host-generated MediaTek compiled bytecode is not compatible with the device's
  Neuron runtime line, even after the export graph was fixed enough to compile

## Attempt Timeline

### 1. App-side model routing and UX

- kept the app's single backend selector UX intact instead of splitting toggles
- mapped coach-model selection so Gemma debug flows hit
  `gemma-4-E2B-it.litertlm`
- exposed the debug smoke test for Gemma in the onboarding/profile screen and in
  the debug `MainActivity` intent hook

Primary app files touched:

- `android/app/src/main/java/com/dreef3/weightlossapp/app/MainActivity.kt`
- `android/app/src/main/java/com/dreef3/weightlossapp/app/di/AppContainer.kt`
- `android/app/src/main/java/com/dreef3/weightlossapp/app/media/ModelDescriptor.kt`
- `android/app/src/main/java/com/dreef3/weightlossapp/chat/CoachModel.kt`
- `android/app/src/main/java/com/dreef3/weightlossapp/chat/CoachModelSupport.kt`
- `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/ProfileEditScreen.kt`

### 2. JNI and native runtime integration

- rebuilt `liblitertlm_jni.so` from local `LiteRT-LM` source
- explicitly bundled `libGemmaModelConstraintProvider.so`
- patched the LiteRT-LM NPU executor so `AUX` runs on CPU LiteRT instead of the
  NPU path
- patched the executor path-selection logic so Gemma 4 can choose the
  per-layer-embedder path from bundle contents, not only from the transformer
  signature

### 3. Export-shape fixes in `litert-torch`

- patched Gemma 4 external RoPE export behavior
- patched Gemma 4 split-cache export behavior
- patched `CacheUpdate` so split-cache AUX export stopped reconstructing generic
  caches
- patched resource-constant lowering for BF16 handling where needed during
  intermediate experiments
- identified `odml.rms_norm` `STABLEHLO_COMPOSITE` as a stable compiler blocker
- forced Gemma 4 RMSNorm HLFB off for the preserved nightly path
- forced Gemma 4 lightweight conversion off for the preserved nightly path

### 4. Stable 2.1.4 host-toolchain attempt

- aligned Python packages to Android runtime availability:
  - `ai-edge-litert==2.1.4`
  - `ai-edge-quantizer==0.6.0`
  - `litert-converter==0.1.0`
- used GCP spot VMs because the Windows/WSL host kept dying mid-export
- stable export completed much further than local WSL
- stable compile still failed in MediaTek `apply_plugin`
- after disabling HLFB composites, stable compile moved from
  `Unsupported operation: EXTENSION` to quantization and host-runtime issues,
  but still did not yield a device-usable bundle

### 5. Nightly host-toolchain attempt

- reused the fixed no-HLFB Gemma 4 export graph
- nightly MediaTek host compile succeeded for the text-path submodels
- nightly bundle packaged successfully
- device still rejected the compiled blob with `NEURON_BAD_DATA`

### 6. Device-side diagnostics

- added verbose MediaTek dispatch logging in a local `LiteRT` checkout
- rebuilt and installed an instrumented APK
- proved the failure ordering:
  1. `model_restore_from_compiled_network` fails
  2. runtime falls back to the DLA-bytecode restore path
  3. fallback path fails at `Failed to finish compilation`

This was the strongest evidence that the remaining blocker is bytecode/runtime
compatibility, not just app packaging.

## Preserved Repositories

### App repo

- branch: `feat/litertlm-npu-setting`
- local worktree: `/home/ae/weight-loss-app/.worktrees/litertlm-npu-setting`

### LiteRT-LM fork

- fork: `https://github.com/dreef3/LiteRT-LM`
- branch: `dreef3/mt6985-npu-preservation`
- commit: `4cff23c144c28ca6497502cde42ebf0f2c3de7b3`
- purpose:
  - preserve CPU-AUX executor patch
  - preserve bundle-based per-layer-embedder path selection patch
  - preserve Bazel workspace adjustment used by the local Android build path

### litert-torch fork

- fork: `https://github.com/dreef3/litert-torch`
- branch: `dreef3/mt6985-gemma4-export-fixes`
- commit: `532a12472b1e15e08ead61a3eb8f718b002dbab4`
- purpose:
  - preserve Gemma 4 split-cache/export fixes
  - preserve BF16 constant-lowering fixes
  - preserve compiler-friendlier Gemma 4 export graph changes

### LiteRT fork

- fork: `https://github.com/dreef3/LiteRT`
- branch: `dreef3/mt6985-dispatch-diagnostics`
- commit: `a2e573b7f5134140605fc91e488fb8e90e3729a0`
- purpose:
  - preserve MediaTek dispatch diagnostics that exposed
    `NEURON_BAD_DATA`
  - preserve the experimental property shim source/target for missing platform
    property cases

Important note:

- the LiteRT fork branch is primarily a diagnostics-preservation branch, not a
  proven production fix branch

## Scripts And Paths To Reuse

Primary local helpers in this repo:

- `tools/build_mt6985_gemma3_litertlm.py`
- `tools/run_stable_export_cache128.sh`
- `tools/run_nightly_export_cache128.sh`
- `tools/gcp/create_spot_vm.sh`
- `tools/gcp/sync_export_workspace.sh`
- `tools/gcp/run_stable_export_on_vm.sh`
- `tools/gcp/run_nightly_export_on_vm.sh`
- `tools/gcp/fetch_export_artifact.sh`
- `tools/gcp/delete_spot_vm.sh`

Most important local artifact preserved:

- `/home/ae/src/model-artifacts/gemma4-mt6985-nightly-nohlfb.litertlm`

Primary deep-dive note:

- `docs/mediatek-mt6985-gemma4-export.md`

## Main Blockers Encountered

1. WSL host instability during full Gemma 4 export
2. stable 2.1.4 MediaTek compiler rejecting the original Gemma 4 graph with
   `STABLEHLO_COMPOSITE` / `EXTENSION`
3. AUX compile needing MVPU/OpenCL-side host pieces not available on the host
   path we had
4. nightly-compiled bytecode being rejected on device as `NEURON_BAD_DATA`
5. wireless ADB instability during repeated APK installs and smoke tests

## Ruled-Out Explanations

These were investigated and are not the best current explanation anymore:

- app selecting the wrong model bundle
- app hiding the smoke test for Gemma
- missing `libGemmaModelConstraintProvider.so`
- stale app-private compiled caches after bundle swap
- missing dispatch initialization on device
- generic JNI loading failure
- only the quantization recipe being wrong

## Best Next NPU Experiment If Revisited

Keep the preserved no-HLFB Gemma 4 export graph, but vary the MediaTek host SDK
line used for compilation.

The builder now supports:

- `MEDIATEK_SDK_LIB_DIR=/absolute/path/to/host/lib`
- `MEDIATEK_SDK_LIB_SUBDIR=v8_0_8/host/lib`

Recommended first retry:

- rebuild the same nightly bundle using `v8_0_8` host libs instead of the newer
  default host SDK line, then re-run the device restore test

## Non-NPU Pivot Context

During follow-up research, no public Xiaomi HyperOS third-party Android LLM SDK
was found that looks like a drop-in replacement for the app's local inference
runtime. Xiaomi does publish:

- HyperOS service SDKs
- Agent/Skill/MCP ecosystem docs for `Miclaw`
- official `MiMo` models and server-side deployment docs

But none of those exposed a public embeddable Android on-device LLM runtime for
normal third-party APKs.
