#!/usr/bin/env bash
# Attempt 48: v9_0_3 + import_forever=false + FC bias NOP + MapReshapeOps NOP
#             + neuron_shim (forces ALL RESHAPE + FC to CPU via getSupportedOperations).
#
# Analysis of Att46/47:
#   - Att46: both NOPs → 1291 ops selected → NoExecPlan (RESHAPE slips into NPU subgraphs)
#   - Att47: MapReshapeOps restored → MapReshapeOps fires → 0 ops (fatal non-SIGABRT error)
#
# Fix: MapReshapeOps NOP (prevents 0-ops crash) + neuron_shim (forces RESHAPE + FC to CPU
#      in getSupportedOperations post-processing, so neither enters any NPU subgraph).
#
# Modified neuron_shim.c:
#   - Forces ALL RESHAPE (type 22) to CPU (not just INT32 input[0])
#   - Forces ALL FC (type 9) to CPU (consistent with whitelist fallback)
#   - RESHAPE removed from whitelist fallback
#   - Extension ops: unchanged (forced to CPU)
#
# Shim build: replaces v9_0_3 libneuron_adapter.so with shim that links real SO.
# protect_dla_dir.so redirects dlopen("libneuron_adapter.so") → shim.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att48-v9-shim.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 48: v9_0_3 + import_forever=false + FC bias NOP + MapReshapeOps NOP + shim ==="
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

# 3. Set up v9_0_3 SO with MapReshapeOps NOP + FC bias NOP
V9_SO=$(find "$VENV" -path '*/v9_0_3/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V9_SO" ]]; then
  echo "ERROR: v9_0_3 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v9_0_3 NeuronAdapter: $V9_SO"
V9_DIR="$(dirname "$V9_SO")"

BAK="${V9_SO}.bak"
if [[ ! -f "$BAK" ]]; then
  echo "ERROR: No backup at $BAK — cannot restore clean state" >&2; exit 1
fi

echo "Restoring SO from backup (original, no patches)..."
cp "$BAK" "$V9_SO"

. "$VENV/bin/activate"

# Apply MapReshapeOps NOP (prevents fatal getSupportedOperations crash in child)
"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V9_SO"

# Apply FC bias NOP (prevents FC bias crash in getSupportedOperations child)
"$VENV/bin/python" "$TOOLS_DIR/patch_fc_bias_check.py" "$V9_SO"

# 4. Build neuron_shim and install it as the v9_0_3 libneuron_adapter.so
#    The shim links the real (patched) SO as libneuron_adapter_real.so.
REAL_SO="${V9_DIR}/libneuron_adapter_real.so"
echo "Copying patched SO to: $REAL_SO"
cp "$V9_SO" "$REAL_SO"

# Create SONAME symlink that the shim's NEEDED entry expects.
# The real SO has SONAME=libneuron_adapter.so.9; the shim links against it,
# so the dynamic linker resolves NEEDED[libneuron_adapter.so.9] via $ORIGIN.
ln -sf "libneuron_adapter_real.so" "${V9_DIR}/libneuron_adapter.so.9" 2>/dev/null || true

echo "Building neuron_shim.c..."
gcc -shared -fPIC -o "$V9_SO" "$TOOLS_DIR/neuron_shim.c" \
  -L"$V9_DIR" \
  -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
  "-Wl,-rpath,\$ORIGIN" \
  -ldl -lpthread \
  -O2 -Wall
echo "Shim built at: $V9_SO ($(du -sh "$V9_SO" | cut -f1))"

# 5. Run AOT compile with v9_0_3 shim + protect_dla_dir.so redirect
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

echo "=== Running AOT compile with v9_0_3 shim on original model ==="
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
