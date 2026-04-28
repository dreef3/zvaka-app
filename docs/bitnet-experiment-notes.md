# BitNet Experiment Notes

These notes capture the practical constraints from `microsoft/BitNet` that matter
if we try to embed it into this Android app later.

Repo:

- `https://github.com/microsoft/BitNet`

## High-Level Fit

- `bitnet.cpp` is a chat/text runtime only in the context of this app
- it is not a drop-in multimodal/photo-estimation replacement
- if we add it, photo estimation still needs a different backend

## Important Implementation Reality

- the current public project is not an Android SDK
- it is a C++/Python inference stack that would need Android-native embedding,
  packaging, and JNI glue similar to what we already did for `llama.cpp`
- it is based on `llama.cpp`, but it is not just a model swap; the kernels and
  model layout are specialized for BitNet models

## Official / Supported Model Notes

Official model called out in the repo:

- `microsoft/BitNet-b1.58-2B-4T`

README notes that the official 2.4B model on ARM supports:

- `I2_S`: yes
- `TL1`: yes
- `TL2`: no

That means on ARM/Android we should not assume all kernel layouts are usable.
For the official model, the safe kernel choices are `I2_S` or `TL1`.

Other supported models on ARM vary by kernel support:

- `1bitLLM/bitnet_b1_58-large` (0.7B): `I2_S` and `TL1` on ARM
- `1bitLLM/bitnet_b1_58-3B` (3.3B): `TL1` only on ARM
- `HF1BitLLM/Llama3-8B-1.58-100B-tokens`: `I2_S` and `TL1` on ARM
- Falcon3 / Falcon-E families: `I2_S` and `TL1` on ARM

## Performance Notes Worth Preserving

From the project README:

- ARM CPU speedups claimed versus their baselines are roughly `1.37x` to `5.07x`
- ARM energy reduction claimed is roughly `55.4%` to `70.0%`

From `gpu/README.md`:

- GPU path is currently framed around custom `W2A8` kernels
- performance numbers shown there are NVIDIA A100 CUDA results, not Android
  Vulkan/Adreno/Mali numbers
- those GPU benchmarks are useful as proof of concept, but they are not evidence
  that Android GPU integration will be easy or performant on this phone

## Proper Kernel / Layout Usage Notes

The repo emphasizes that performance depends on using the right quant/kernel
layout for the specific model and architecture.

Important examples:

- official `BitNet-b1.58-2B-4T` uses model artifacts prepared for BitNet kernel
  expectations
- setup flow uses `setup_env.py` with explicit `--quant-type` such as `i2_s` or
  `tl1`
- the GPU-side docs describe weight permutation and packed 2-bit layouts that are
  specific to BitNet's kernel path

Practical implication for this app:

- if we experiment with BitNet later, we should start with the official 2B model
  and the ARM-supported kernel path the repo expects
- we should not assume arbitrary GGUF-like swaps will behave well

## Android Risk Notes

- current repo requirements mention `cmake>=3.22` and `clang>=18`
- no Android/Gradle/AAR packaging flow is provided upstream
- no multimodal path is provided for our photo-estimation use case
- even if chat integration is possible, it would be another native runtime to
  vendor and maintain alongside `llama.cpp`

## Recommended Future Experiment Order

If BitNet is revisited later, the safest starting point is:

1. text/chat only
2. official `BitNet-b1.58-2B-4T`
3. ARM-supported kernel path (`I2_S` first, `TL1` second)
4. compare against the already revived `llama.cpp + Vulkan` Gemma path on the
   same device before committing to deeper integration
