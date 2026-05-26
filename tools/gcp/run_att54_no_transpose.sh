#!/usr/bin/env bash
# Attempt 54: Force TRANSPOSE (type 37) to CPU in addition to RESHAPE/FC/MUL.
#
# Att53 (hybrid: Att51 prefill + Att50 embedder) failed at device partition 540/573:
#   NeuronModel_restoreFromCompiledNetwork — Failed to load compiled network
#
# Pattern analysis:
#   Att50 (RESHAPE+FC → CPU): fails partition 480/573
#   Att53 (RESHAPE+FC+MUL → CPU): fails partition 540/573
#   Delta: +60 successful partitions when MUL moved to CPU.
#
# Two hypotheses:
#   A) APUSys DRAM exhaustion: loading 573 DLAs fills ~18 MB device memory; each
#      reduction moves the threshold up.  Expected: failure moves past 573 if we
#      remove enough DLAs.
#   B) 0-byte DLA blobs: NeuronCompilation_finish fails for specific partitions at
#      compile time → fake-success writes 0-byte DLAs into .tflite → device calls
#      NeuronModel_restoreFromCompiledNetwork with 0 bytes → hard failure.
#
# Fix for Att54: force TRANSPOSE (type 37) to CPU.
#   - Transformer models have many TRANSPOSE ops for attention pattern reshaping;
#     removing them from NPU reduces both total DLA count (hypothesis A) and
#     eliminates potential 0-byte TRANSPOSE DLAs (hypothesis B).
#   - neuron_shim.c updated: TRANSPOSE added to forced-CPU list.
#   - neuron_shim.c updated: DLA sizes now logged in getCompiledNetworkSize and
#     storeCompiledNetwork for real (non-fake) compilations — diagnosis aid.
#
# Strategy:
#   - Fresh recompile of prefill_decode with new shim (RESHAPE/FC/MUL/TRANSPOSE → CPU)
#   - Attempt embedder recompile (non-fatal; skip if 0 NPU ops)
#   - Bundle → gemma3-270m-att54.litertlm
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-notranspose"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
OUTPUT_LITERTLM="$OUTPUT_DIR/gemma3-270m-att54.litertlm"
LOG_FILE="$ROOT/tmp/att54-no-transpose.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 54: v8_0_10 + MapReshapeOps NOP + shim (RESHAPE/FC/MUL/TRANSPOSE → CPU) ==="
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

# 4. Build neuron_shim (RESHAPE/FC/MUL/TRANSPOSE all forced to CPU)
REAL_SO="${V8_DIR}/libneuron_adapter_real.so"
echo "Copying patched SO to: $REAL_SO"
cp "$V8_SO" "$REAL_SO"

ln -sf "libneuron_adapter_real.so" "${V8_DIR}/libneuron_adapter.so.8" 2>/dev/null || true

echo "Building neuron_shim.c (RESHAPE/FC/MUL/TRANSPOSE → CPU)..."
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
    echo "WARNING: embedder compilation FAILED (likely 0 NPU ops) — bundle will run embedder on CPU"
  fi
fi

# 6. Bundle
export HF_DATASETS_OFFLINE=1; export TRANSFORMERS_OFFLINE=1

echo "=== Bundling Att54 ==="
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

echo "=== Att54 DONE ==="
echo "To push to device:"
echo "  adb -s 100.104.183.118:5555 push $OUTPUT_LITERTLM /data/local/tmp/gemma3-270m-att54.litertlm"
echo "  adb -s 100.104.183.118:5555 shell run-as com.dreef3.weightlossapp.debug"
echo "    cp /data/local/tmp/gemma3-270m-att54.litertlm cache/models/gemma-3-270m-it_mt6985.litertlm"
date -Is
