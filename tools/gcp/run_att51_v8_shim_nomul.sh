#!/usr/bin/env bash
# Attempt 51: v8_0_10 + import_forever=false + MapReshapeOps NOP + neuron_shim
#             with MUL (type 18) also forced to CPU.
#
# Att50 failed: partition 480 of prefill_decode DLA fails
# NeuronModel_restoreFromCompiledNetwork on device.
# Root cause: MUL ops (type 18) marked as NPU-supported by v8_0_10
# getSupportedOperations but the resulting DLA format is rejected by adapter
# 8.2.26 at inference time (MUL broadcast-shape subgraph issue from Att14-28).
#
# Fix: force MUL (type 18) to CPU in neuron_shim.c, consistent with the whitelist
# fallback which already excluded MUL.
#
# All other parameters identical to Att50:
#   - SDK: v8_0_10
#   - SONAME symlink: libneuron_adapter.so.8
#   - MapReshapeOps NOP only (no FC bias patch)
#   - RESHAPE (22) + FC (9) + MUL (18) forced to CPU
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-nomul"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att51-v8-shim-nomul.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 51: v8_0_10 + MapReshapeOps NOP + shim (RESHAPE/FC/MUL → CPU) ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2; exit 1
fi
MODEL="$EXPORT_WORK/model_quantized.tflite"
if [[ ! -f "$MODEL" ]]; then
  echo "ERROR: model_quantized.tflite not found at $EXPORT_WORK/" >&2; exit 1
fi
EMBEDDER="$EXPORT_WORK/embedder_quantized.tflite"
if [[ ! -f "$EMBEDDER" ]]; then
  echo "WARNING: embedder_quantized.tflite not found — embedder will be skipped"
  EMBEDDER=""
fi

# 1. Ensure import_forever=false is patched in plugin SO
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"

# 2. Rebuild protect_dla_dir.so if missing
if [[ ! -f "$PROTECT_SO" ]]; then
  echo "Building protect_dla_dir.so..."
  gcc -shared -fPIC -o "$PROTECT_SO" "$SCRIPT_DIR/protect_dla_dir.c" -ldl
fi
echo "protect_dla_dir.so: $PROTECT_SO"

# 3. Set up v8_0_10 SO with MapReshapeOps NOP
V8_SO=$(find "$VENV" -path '*/v8_0_10/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
if [[ -z "$V8_SO" ]]; then
  echo "ERROR: v8_0_10 libneuron_adapter.so not found in $VENV" >&2; exit 1
fi
echo "v8_0_10 NeuronAdapter: $V8_SO"
V8_DIR="$(dirname "$V8_SO")"

BAK="${V8_SO}.bak"
if [[ -f "$BAK" ]]; then
  echo "Restoring SO from backup (clean, pre-patch)..."
  cp "$BAK" "$V8_SO"
else
  echo "No backup found — creating $BAK from current SO..."
  cp "$V8_SO" "$BAK"
fi

. "$VENV/bin/activate"

"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V8_SO"

# 4. Build neuron_shim (updated: RESHAPE+FC+MUL all forced to CPU)
REAL_SO="${V8_DIR}/libneuron_adapter_real.so"
echo "Copying patched SO to: $REAL_SO"
cp "$V8_SO" "$REAL_SO"

ln -sf "libneuron_adapter_real.so" "${V8_DIR}/libneuron_adapter.so.8" 2>/dev/null || true

echo "Building neuron_shim.c (RESHAPE/FC/MUL → CPU)..."
gcc -shared -fPIC -o "$V8_SO" "$TOOLS_DIR/neuron_shim.c" \
  -L"$V8_DIR" \
  -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
  "-Wl,-rpath,\$ORIGIN" \
  -ldl -lpthread \
  -O2 -Wall
echo "Shim built at: $V8_SO ($(du -sh "$V8_SO" | cut -f1))"

# 5. Run AOT compile
mkdir -p "$COMPILED_DIR"

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
export MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib
export NEURON_ADAPTER_OVERRIDE_SO="$V8_SO"
export PROTECT_DLA_SO="$PROTECT_SO"

echo "=== Running AOT compile (prefill_decode) ==="
rm -rf "$COMPILED_DIR/prefill_decode" "$COMPILED_DIR/prefill_decode.tflite"
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$MODEL" \
  --output-dir "$COMPILED_DIR/prefill_decode" \
  --label prefill_decode \
  --sdk-subdir v8_0_10/host/lib

COMPILED="$COMPILED_DIR/prefill_decode.tflite"
if [[ -f "$COMPILED" && -s "$COMPILED" ]]; then
  echo "=== prefill_decode SUCCEEDED: $COMPILED ($(du -sh "$COMPILED" | cut -f1)) ==="
else
  echo "ERROR: prefill_decode output missing or empty" >&2; exit 1
fi

if [[ -n "$EMBEDDER" ]]; then
  echo "=== Running AOT compile (embedder) ==="
  rm -rf "$COMPILED_DIR/embedder" "$COMPILED_DIR/embedder.tflite"
  nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
    --input "$EMBEDDER" \
    --output-dir "$COMPILED_DIR/embedder" \
    --label embedder \
    --sdk-subdir v8_0_10/host/lib

  COMPILED_EMB="$COMPILED_DIR/embedder.tflite"
  if [[ -f "$COMPILED_EMB" && -s "$COMPILED_EMB" ]]; then
    echo "=== embedder SUCCEEDED: $COMPILED_EMB ($(du -sh "$COMPILED_EMB" | cut -f1)) ==="
  else
    echo "ERROR: embedder output missing or empty" >&2; exit 1
  fi
fi

echo "=== Att51 compilation DONE ==="
date -Is
