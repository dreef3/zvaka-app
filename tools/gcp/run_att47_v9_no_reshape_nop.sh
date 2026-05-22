#!/usr/bin/env bash
# Attempt 47: v9_0_3 + import_forever=false + FC bias NOP only (NO MapReshapeOps NOP).
#
# Att46 analysis: 1291 ops were selected (partitioning worked!), but compilation hit
# "Compile error: NoExecPlan" because RESHAPE ops passed MapReshapeOps NOP, entered
# NPU subgraphs, and MDLA rejected them at compile time ("Unsupported layer").
#
# Root cause: the MapReshapeOps check is a GetSupportedOperations filter. NOPping it
# allows RESHAPE into NPU partitions, which causes hardware NoExecPlan.
# Without the NOP, RESHAPE is rejected at GetSupportedOperations → stays on CPU.
#
# Fix: restore v9_0_3 SO to the pre-MapReshapeOps-NOP state, then apply ONLY the
# FC bias NOP. RESHAPE ops will stay on CPU; FC ops are accepted by the filter (bias
# check NOPped) and fall back to CPU via hardware target report (data type mismatch).
#
# No fix_dynamic_reshape needed: RESHAPE ops run on CPU which handles dynamic shapes.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att47-v9-no-reshape-nop.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 47: v9_0_3 + import_forever=false + FC bias NOP only ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2; exit 1
fi
MODEL="$EXPORT_WORK/model_quantized.tflite"
if [[ ! -f "$MODEL" ]]; then
  echo "ERROR: model_quantized.tflite not found at $EXPORT_WORK/" >&2; exit 1
fi

# 1. Ensure import_forever=false is patched in plugin SO
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"

# 2. Rebuild protect_dla_dir.so if missing
if [[ ! -f "$PROTECT_SO" ]]; then
  echo "Building protect_dla_dir.so..."
  gcc -shared -fPIC -o "$PROTECT_SO" "$SCRIPT_DIR/protect_dla_dir.c" -ldl
fi
echo "protect_dla_dir.so: $PROTECT_SO"

# 3. Restore v9_0_3 SO from backup (before MapReshapeOps NOP was applied)
#    then apply ONLY the FC bias NOP.
V9_SO=$(find "$VENV" -path '*/v9_0_3/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V9_SO" ]]; then
  echo "ERROR: v9_0_3 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v9_0_3 NeuronAdapter: $V9_SO"

BAK="${V9_SO}.bak"
if [[ ! -f "$BAK" ]]; then
  echo "ERROR: No backup at $BAK — cannot restore pre-MapReshapeOps state" >&2; exit 1
fi

echo "Restoring SO from backup (reverts MapReshapeOps NOP)..."
cp "$BAK" "$V9_SO"
echo "Restored."

# Now apply ONLY the FC bias check NOP (do NOT apply patch_v8_mapresha.py)
. "$VENV/bin/activate"
"$VENV/bin/python" "$TOOLS_DIR/patch_fc_bias_check.py" "$V9_SO"

# 4. Run AOT compile with v9_0_3 + original model (no fix_dynamic_reshape)
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

echo "=== Running AOT compile with v9_0_3 on original model ==="
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$MODEL" \
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
