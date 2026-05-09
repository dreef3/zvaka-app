#!/usr/bin/env bash
# Attempt 6: prefill_256 + cache_256.
# Predicted allocation: 2,415,919,104 / 256 + 16,384 = 9,453,568 (~9.4MB) — fits in ~16MB APUSys pool.
# All attempts with prefill <= 64 yield 37.75MB; only prefill >= 256 predicted to fit.
# Run on the GCP spot VM via: tools/gcp/run_nightly_export_on_vm.sh with SCRIPT=this_file
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert"
OUTPUT_DIR="$ROOT/tmp/gemma4-mt6985-build-256-p256"
LOG_FILE="$ROOT/tmp/gemma4-mt6985-build-256-p256.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing nightly export environment: $VENV" >&2
  echo "Expected Python at: $VENV/bin/python" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "Starting Gemma4 MT6985 export — Attempt 6 (prefill_256, cache_256)"
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
export GEMMA4_DISABLE_RMS_HLFB=1
export GEMMA4_LIGHTWEIGHT_CONVERSION=0
export MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-4-e2b-it \
  --model-family gemma4 \
  --cache-length 256 \
  --prefill-lengths 256 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
