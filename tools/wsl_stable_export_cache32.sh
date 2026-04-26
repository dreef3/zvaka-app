#!/usr/bin/env bash
set -euo pipefail

cd /home/ae/src/litert-build

export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1
export TF_NUM_INTRAOP_THREADS=1
export TF_NUM_INTEROP_THREADS=1
export XLA_PYTHON_CLIENT_PREALLOCATE=false
export JAX_PLATFORMS=cpu
export JAX_PLATFORM_NAME=cpu
export CUDA_VISIBLE_DEVICES=
export RESOURCE_CONSTANT_NUMEL_THRESHOLD=1

. .venv-litert-214/bin/activate

rm -rf /home/ae/src/litert-build/tmp/gemma4-mt6985-build-32-stable214

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-4-e2b-it \
  --model-family gemma4 \
  --cache-length 32 \
  --prefill-lengths 32 \
  --output-dir /home/ae/src/litert-build/tmp/gemma4-mt6985-build-32-stable214 \
  --keep-intermediates
