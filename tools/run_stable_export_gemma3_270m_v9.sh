#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9"
LOG_FILE="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing stable export environment: $VENV" >&2
  echo "Expected Python at: $VENV/bin/python" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "Starting Gemma 3 270M MT6985 v9_0_3 SDK export"
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
# Try v9_0_2 first — v9_0_3 rejects INT32 biases from dynamic_wi4_afp32 recipe.
# v9_0_2 is expected to produce V245303 DLA (compatible with libneuronusdk_adapter.9.mtk.so)
# with less strict bias type validation.
export MEDIATEK_SDK_LIB_SUBDIR=v9_0_2/host/lib

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-3-270m-it \
  --model-family gemma3 \
  --cache-length 128 \
  --prefill-lengths 128 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
