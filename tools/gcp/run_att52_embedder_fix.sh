#!/usr/bin/env bash
# Attempt 52: Fix embedder compilation from Att51.
#
# Att51 succeeded for prefill_decode but the embedder failed with:
#   "apply_plugin failed": MAXIMUM(Int32) went to NPU, MDLA rejected it with
#   "Cannot support Int32 input". Forcing MUL to CPU (Att51) changed the
#   partitioning so MAXIMUM ended up in its own NPU partition.
#
# Fix: neuron_shim.c now also forces any op whose first input is INT32 to CPU.
#   This catches MAXIMUM(Int32) and any other Int32-typed ops generically.
#
# Strategy:
#   - Reuse Att51's compiled prefill_decode.tflite (already good, MUL on CPU)
#   - Rebuild shim (with INT32 check) and recompile embedder only
#   - Bundle Att51 prefill + Att52 embedder → gemma3-270m-att52.litertlm
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
ATT51_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-nomul"
ATT51_PREFILL="$ATT51_DIR/compiled_mt6985/prefill_decode.tflite"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-noint32"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
OUTPUT_LITERTLM="$OUTPUT_DIR/gemma3-270m-att52.litertlm"
LOG_FILE="$ROOT/tmp/att52-embedder-fix.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 52: recompile embedder with INT32-input filter ==="
date -Is

if [[ ! -f "$ATT51_PREFILL" || ! -s "$ATT51_PREFILL" ]]; then
  echo "ERROR: Att51 prefill not found: $ATT51_PREFILL" >&2; exit 1
fi
echo "Reusing Att51 prefill: $ATT51_PREFILL ($(du -sh "$ATT51_PREFILL" | cut -f1))"

EMBEDDER="$EXPORT_WORK/embedder_quantized.tflite"
if [[ ! -f "$EMBEDDER" ]]; then
  echo "WARNING: embedder_quantized.tflite not found — will bundle without embedder"
  EMBEDDER=""
fi

# 1. Ensure import_forever=false is patched
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"

# 2. Rebuild protect_dla_dir.so if missing
if [[ ! -f "$PROTECT_SO" ]]; then
  gcc -shared -fPIC -o "$PROTECT_SO" "$SCRIPT_DIR/protect_dla_dir.c" -ldl
fi

# 3. Set up v8_0_10 SO with MapReshapeOps NOP
V8_SO=$(find "$VENV" -path '*/v8_0_10/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V8_SO" ]]; then
  echo "ERROR: v8_0_10 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v8_0_10 NeuronAdapter: $V8_SO"
V8_DIR="$(dirname "$V8_SO")"

BAK="${V8_SO}.bak"
if [[ -f "$BAK" ]]; then
  cp "$BAK" "$V8_SO"
else
  cp "$V8_SO" "$BAK"
fi

. "$VENV/bin/activate"
"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V8_SO"

# 4. Build updated shim (RESHAPE+FC+MUL+INT32-input → CPU)
REAL_SO="${V8_DIR}/libneuron_adapter_real.so"
cp "$V8_SO" "$REAL_SO"
ln -sf "libneuron_adapter_real.so" "${V8_DIR}/libneuron_adapter.so.8" 2>/dev/null || true

echo "Building neuron_shim.c (RESHAPE/FC/MUL/INT32-input → CPU)..."
gcc -shared -fPIC -o "$V8_SO" "$TOOLS_DIR/neuron_shim.c" \
  -L"$V8_DIR" \
  -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
  "-Wl,-rpath,\$ORIGIN" \
  -ldl -lpthread \
  -O2 -Wall
echo "Shim built: $V8_SO ($(du -sh "$V8_SO" | cut -f1))"

# 5. Set up output dir with Att51's prefill
mkdir -p "$COMPILED_DIR"
cp "$ATT51_PREFILL" "$COMPILED_DIR/prefill_decode.tflite"
echo "Copied prefill from Att51: $(du -sh "$COMPILED_DIR/prefill_decode.tflite" | cut -f1)"

# 6. Recompile embedder with updated shim
export OMP_NUM_THREADS=1; export OPENBLAS_NUM_THREADS=1; export MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1; export TF_NUM_INTRAOP_THREADS=1; export TF_NUM_INTEROP_THREADS=1
export XLA_PYTHON_CLIENT_PREALLOCATE=false; export JAX_PLATFORMS=cpu; export JAX_PLATFORM_NAME=cpu
export CUDA_VISIBLE_DEVICES=; export MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib
export NEURON_ADAPTER_OVERRIDE_SO="$V8_SO"; export PROTECT_DLA_SO="$PROTECT_SO"

if [[ -n "$EMBEDDER" ]]; then
  echo "=== Recompiling embedder with INT32-input filter ==="
  rm -rf "$COMPILED_DIR/embedder" "$COMPILED_DIR/embedder.tflite"
  if nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
    --input "$EMBEDDER" \
    --output-dir "$COMPILED_DIR/embedder" \
    --label embedder \
    --sdk-subdir v8_0_10/host/lib; then
    COMPILED_EMB="$COMPILED_DIR/embedder.tflite"
    if [[ -f "$COMPILED_EMB" && -s "$COMPILED_EMB" ]]; then
      echo "=== embedder SUCCEEDED: $COMPILED_EMB ($(du -sh "$COMPILED_EMB" | cut -f1)) ==="
    else
      echo "WARNING: embedder output missing/empty — bundle will run embedder on CPU"
    fi
  else
    echo "WARNING: embedder compilation FAILED — bundle will run embedder on CPU"
  fi
fi

# 7. Bundle Att52 (Att51 prefill + Att52 embedder or Att50 fallback)
export HF_DATASETS_OFFLINE=1; export TRANSFORMERS_OFFLINE=1

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
