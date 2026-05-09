#!/usr/bin/env bash
# Gemma 3 1B IT, prefill_128 + cache_128 targeting MT6985.
# Hypothesis: 1B model has ~0.3x the DLA working-buffer of Gemma 4 2B, so
# the (128,128) allocation (~18.9MB for 2B) should drop below the ~16MB pool.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert"
OUTPUT_DIR="$ROOT/tmp/gemma3-1b-mt6985-p128-c128"
LOG_FILE="$ROOT/tmp/gemma3-1b-mt6985-p128-c128.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing nightly export environment: $VENV" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "Starting Gemma3 1B IT MT6985 export — prefill_128, cache_128"
date -Is
echo "Output dir: $OUTPUT_DIR"
echo "Log file: $LOG_FILE"

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
export MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-3-1b-it \
  --model-family gemma3 \
  --cache-length 128 \
  --prefill-lengths 128 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
