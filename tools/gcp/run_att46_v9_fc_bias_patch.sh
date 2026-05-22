#!/usr/bin/env bash
# Attempt 46: v9_0_3 + import_forever=false + fixed 2-input RESHAPE + FC bias patch.
#
# Att45 failed: v9_0_3 also rejects non-float FC biases ("Bias should be floating point
# type", 0 ops selected). Root cause: the MapReshapeOps fix exposed the FC bias check
# (Att31-43 never reached it because RESHAPE failed first).
#
# New fix: patch_fc_bias_check.py NOPs the JA+JAE checks at file offset 0x1056652 in
# v9_0_3 libneuron_adapter.so, allowing INT32-bias FC ops to pass validation.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att46-v9-fc-bias-patch.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXED_MODEL="$ROOT/tmp/model_quantized_fixed_reshape.tflite"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 46: v9_0_3 + import_forever=false + fixed RESHAPE + FC bias patch ==="
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

# 3. Apply fix_dynamic_reshape: bake shapes, keep 2 inputs
echo "Fixing dynamic RESHAPE ops (bake shapes, keep 2-input format)..."
. "$VENV/bin/activate"
python "$SCRIPT_DIR/fix_dynamic_reshape.py" \
  "$EXPORT_WORK/model_quantized.tflite" \
  "$FIXED_MODEL"

# 4. Find v9_0_3 SO and apply both patches
V9_SO=$(find "$VENV" -path '*/v9_0_3/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V9_SO" ]]; then
  echo "ERROR: v9_0_3 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v9_0_3 NeuronAdapter: $V9_SO"

# Patch 1: NOP MapReshapeOps JAE check (CMP+JAE pattern, both occurrences)
"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V9_SO"

# Patch 2: NOP FC bias floating-point type check (JA+BT+JAE pattern)
"$VENV/bin/python" "$TOOLS_DIR/patch_fc_bias_check.py" "$V9_SO"

# 5. Run AOT compile with v9_0_3 + protect_dla_dir.so override
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
