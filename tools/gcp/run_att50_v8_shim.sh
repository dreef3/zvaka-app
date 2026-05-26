#!/usr/bin/env bash
# Attempt 50: v8_0_10 + import_forever=false + MapReshapeOps NOP + neuron_shim.
#
# Motivation:
#   Att49 used v9_0_3 SDK → DLA format rejected by device's base NeuronAdapter
#   8.2.26 (NeuronModel_restoreFromCompiledNetwork failed).  v8_0_10 SDK should
#   produce a DLA format compatible with adapter 8.2.26 (SONAME libneuron_adapter.so.8).
#
# Changes vs Att49:
#   - Switch compilation SDK from v9_0_3 → v8_0_10
#   - SONAME symlink: libneuron_adapter.so.8 (not .9)
#   - --sdk-subdir v8_0_10/host/lib
#   - No patch_fc_bias_check.py: v8_0_10 uses BT ecx,eax (0f a3 c1) — different
#     pattern.  Instead, neuron_shim.c detects all-zero child result and falls
#     back to the NPU whitelist (ADD, CONCAT, SOFTMAX, MEAN, SUB, TRANSPOSE,
#     GREATER, BATCH_MATMUL); FC and RESHAPE go to CPU.
#   - Compile BOTH prefill_decode AND embedder with v8_0_10 shim.
#   - New output dir: gemma3-270m-mt6985-p128-c128-v8-shim/
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
LOG_FILE="$ROOT/tmp/att50-v8-shim.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 50: v8_0_10 + import_forever=false + MapReshapeOps NOP + shim ==="
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
  echo "WARNING: embedder_quantized.tflite not found at $EXPORT_WORK/ — embedder will be skipped"
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

# 3. Set up v8_0_10 SO with MapReshapeOps NOP only.
#    FC bias check uses a different pattern in v8_0_10 (BT ecx,eax = 0f a3 c1
#    vs v9's BT rdx,rax = 48 0f a3 c2) so patch_fc_bias_check.py does not apply.
#    neuron_shim.c now detects all-zero child result and uses the NPU whitelist.
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

# Apply MapReshapeOps NOP (prevents getSupportedOperations from returning 0 ops
# due to the CMP ecx,2 + JAE check that fires for RESHAPE ops).
"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V8_SO"

# 4. Build neuron_shim and install it as the v8_0_10 libneuron_adapter.so.
#    The shim links the real (MapReshapeOps-patched) SO as libneuron_adapter_real.so.
REAL_SO="${V8_DIR}/libneuron_adapter_real.so"
echo "Copying patched SO to: $REAL_SO"
cp "$V8_SO" "$REAL_SO"

# Create SONAME symlink: v8_0_10 SO has SONAME=libneuron_adapter.so.8.
# The shim links against -lneuron_adapter_real, and the dynamic linker resolves
# NEEDED[libneuron_adapter.so.8] via the $ORIGIN rpath → libneuron_adapter_real.so.
ln -sf "libneuron_adapter_real.so" "${V8_DIR}/libneuron_adapter.so.8" 2>/dev/null || true

echo "Building neuron_shim.c against v8_0_10..."
gcc -shared -fPIC -o "$V8_SO" "$TOOLS_DIR/neuron_shim.c" \
  -L"$V8_DIR" \
  -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
  "-Wl,-rpath,\$ORIGIN" \
  -ldl -lpthread \
  -O2 -Wall
echo "Shim built at: $V8_SO ($(du -sh "$V8_SO" | cut -f1))"

# 5. Run AOT compile with v8_0_10 shim + protect_dla_dir.so redirect
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

echo "=== Running AOT compile (prefill_decode) with v8_0_10 shim ==="
rm -rf "$COMPILED_DIR/prefill_decode" "$COMPILED_DIR/prefill_decode.tflite"
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$MODEL" \
  --output-dir "$COMPILED_DIR/prefill_decode" \
  --label prefill_decode \
  --sdk-subdir v8_0_10/host/lib

COMPILED="$COMPILED_DIR/prefill_decode.tflite"
if [[ -f "$COMPILED" && -s "$COMPILED" ]]; then
  echo "=== prefill_decode AOT SUCCEEDED: $COMPILED ($(du -sh "$COMPILED" | cut -f1)) ==="
else
  echo "ERROR: prefill_decode compiled output missing or empty" >&2
  exit 1
fi

if [[ -n "$EMBEDDER" ]]; then
  echo "=== Running AOT compile (embedder) with v8_0_10 shim ==="
  rm -rf "$COMPILED_DIR/embedder" "$COMPILED_DIR/embedder.tflite"
  nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
    --input "$EMBEDDER" \
    --output-dir "$COMPILED_DIR/embedder" \
    --label embedder \
    --sdk-subdir v8_0_10/host/lib

  COMPILED_EMB="$COMPILED_DIR/embedder.tflite"
  if [[ -f "$COMPILED_EMB" && -s "$COMPILED_EMB" ]]; then
    echo "=== embedder AOT SUCCEEDED: $COMPILED_EMB ($(du -sh "$COMPILED_EMB" | cut -f1)) ==="
  else
    echo "ERROR: embedder compiled output missing or empty" >&2
    exit 1
  fi
fi

echo "=== Att50 compilation DONE ==="
date -Is
