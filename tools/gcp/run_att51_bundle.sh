#!/usr/bin/env bash
# Bundle Att51 compiled artifacts (v8_0_10 shim, MUL forced to CPU).
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-nomul"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
OUTPUT_LITERTLM="$OUTPUT_DIR/gemma3-270m-att51.litertlm"
LOG_FILE="$ROOT/tmp/att51-bundle.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Att51 bundle: assembling .litertlm ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2; exit 1
fi
for f in "$COMPILED_DIR/prefill_decode.tflite" "$EXPORT_WORK/tokenizer.json" "$EXPORT_WORK/auxiliary.tflite"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: missing: $f" >&2; exit 1
  fi
  echo "Found: $f ($(du -sh "$f" | cut -f1))"
done

. "$VENV/bin/activate"
export OMP_NUM_THREADS=1; export OPENBLAS_NUM_THREADS=1; export MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1; export JAX_PLATFORMS=cpu; export JAX_PLATFORM_NAME=cpu
export CUDA_VISIBLE_DEVICES=; export HF_DATASETS_OFFLINE=1; export TRANSFORMERS_OFFLINE=1

python "$SCRIPT_DIR/bundle_att49.py" \
  --export-work  "$EXPORT_WORK" \
  --compiled-dir "$COMPILED_DIR" \
  --output       "$OUTPUT_LITERTLM" \
  --model        google/gemma-3-270m-it \
  --cache-length 128

if [[ -f "$OUTPUT_LITERTLM" && -s "$OUTPUT_LITERTLM" ]]; then
  echo "=== Bundle SUCCEEDED: $OUTPUT_LITERTLM ($(du -sh "$OUTPUT_LITERTLM" | cut -f1)) ==="
else
  echo "ERROR: bundle output missing or empty" >&2; exit 1
fi
date -Is
