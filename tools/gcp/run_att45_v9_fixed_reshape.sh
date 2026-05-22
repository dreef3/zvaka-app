#!/usr/bin/env bash
# Attempt 45: v9_0_3 SDK + import_forever=false + fixed RESHAPE (bake shapes, keep 2 inputs).
#
# Root cause of Att31-43 failures: fix_dynamic_reshape.py was removing input 1
# from RESHAPE ops (creating 1-input), which NeuronAdapter v9_0_3 rejects ("Got 1 of 2").
# The fix: bake shape into shape tensor buffer but KEEP both inputs.
#
# Uses the already-exported model_quantized.tflite from the v8 export dir (same model).
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
# Use the freshly-exported model from Att44 (v8 dir has same model as v9)
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att45-v9-fixed-reshape.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXED_MODEL="$ROOT/tmp/model_quantized_fixed_reshape.tflite"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 45: v9_0_3 + import_forever=false + fixed 2-input RESHAPE ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2; exit 1
fi
if [[ ! -f "$EXPORT_WORK/model_quantized.tflite" ]]; then
  echo "ERROR: model_quantized.tflite not found at $EXPORT_WORK/" >&2; exit 1
fi

# 1. Ensure import_forever=false is patched in plugin SO
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"

# 2. Rebuild protect_dla_dir.so if missing or stale
if [[ ! -f "$PROTECT_SO" ]]; then
  echo "Building protect_dla_dir.so..."
  gcc -shared -fPIC -o "$PROTECT_SO" "$SCRIPT_DIR/protect_dla_dir.c" -ldl
fi
echo "protect_dla_dir.so: $PROTECT_SO"

# 3. Apply fix_dynamic_reshape: bake shapes into shape tensor buffers, KEEP 2 inputs
echo "Fixing dynamic RESHAPE ops (bake shapes, keep 2-input format)..."
. "$VENV/bin/activate"
python "$SCRIPT_DIR/fix_dynamic_reshape.py" \
  "$EXPORT_WORK/model_quantized.tflite" \
  "$FIXED_MODEL"

# 4. Run apply_plugin with v9_0_3 + protect_dla_dir.so
mkdir -p "$COMPILED_DIR"
rm -rf "$COMPILED_DIR/prefill_decode" "$COMPILED_DIR/prefill_decode.tflite"

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
export MEDIATEK_SDK_LIB_SUBDIR=v9_0_3/host/lib

# NEURON_ADAPTER_OVERRIDE_SO: protect_dla_dir.so intercepts dlopen("libneuron_adapter.so")
# and redirects it to v9_0_3. Without this, apply_plugin_main falls back to the
# hardcoded third_party/neuro_pilot/v8_latest/host/lib/libneuron_adapter.so (v8_0_10),
# which rejects non-float FC biases and blocks the entire model.
V9_SO=$(find "$VENV" -path '*/v9_0_3/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V9_SO" ]]; then
  echo "ERROR: v9_0_3 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v9_0_3 NeuronAdapter: $V9_SO"

# Patch v9_0_3 to NOP the same MapReshapeOps JAE check as v8_0_10.
# Both use the identical 5-byte pattern; script searches by pattern so it works for both.
SCRIPT_DIR_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$VENV/bin/python" "$SCRIPT_DIR_TOOLS/patch_v8_mapresha.py" "$V9_SO"

export NEURON_ADAPTER_OVERRIDE_SO="$V9_SO"
export PROTECT_DLA_SO="$PROTECT_SO"

echo "=== Running AOT compile with v9_0_3 on fixed model ==="
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$FIXED_MODEL" \
  --output-dir "$COMPILED_DIR/prefill_decode" \
  --label prefill_decode \
  --sdk-subdir v9_0_3/host/lib

COMPILED="$COMPILED_DIR/prefill_decode.tflite"
if [[ -f "$COMPILED" && -s "$COMPILED" ]]; then
  echo "=== AOT compile SUCCEEDED: $COMPILED ($(du -sh "$COMPILED" | cut -f1)) ==="
else
  echo "ERROR: compiled output missing or empty" >&2
  exit 1
fi
echo "Done."
date -Is
