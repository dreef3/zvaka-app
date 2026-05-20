#!/usr/bin/env bash
# Attempt 30: compile Gemma 3 270M for MT6985 without import_forever.
# Patches libLiteRtCompilerPlugin_MediaTek.so before running the export.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
LOG_FILE="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing stable export environment: $VENV" >&2
  echo "Expected Python at: $VENV/bin/python" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "Starting Gemma 3 270M MT6985 v9_0_3 SDK export (no import_forever)"
date -Is
echo "Output dir: $OUTPUT_DIR"
echo "Log file: $LOG_FILE"

# Patch the compiler plugin SO to disable import_forever
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$VENV" bash "$SCRIPT_DIR/gcp/patch_no_import_forever.sh"

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
export MEDIATEK_SDK_LIB_SUBDIR=v9_0_3/host/lib

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-3-270m-it \
  --model-family gemma3 \
  --cache-length 128 \
  --prefill-lengths 128 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
