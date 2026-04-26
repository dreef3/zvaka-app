#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
OUTPUT_DIR="$ROOT/tmp/gemma4-mt6985-build-512-stable214"
LOG_FILE="$ROOT/tmp/gemma4-mt6985-build-512-stable214.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing stable export environment: $VENV" >&2
  echo "Expected Python at: $VENV/bin/python" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "Starting stable 2.1.4 Gemma4 MT6985 export"
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

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-4-e2b-it \
  --model-family gemma4 \
  --cache-length 512 \
  --prefill-lengths 128 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
