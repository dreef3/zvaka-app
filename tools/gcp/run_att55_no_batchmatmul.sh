#!/usr/bin/env bash
# Attempt 55: Force BATCH_MATMUL (type 86) to CPU — diagnostic for "Fail to revise
# dla::CompiledResult" device error.
#
# Att54 findings:
#   - v8_0_10 natively refuses RESHAPE/FC/MUL/TRANSPOSE (shim forced 0 additional ops)
#   - 537 DLAs compiled, ALL non-zero (4–12 KB each) — no fake-success 0-byte blobs
#   - Total DLA data ~3.2 MB (well under 18 MB APUSys DRAM limit)
#   - Device test FAILED: "Fail to revise dla::CompiledResult" /
#     "Fail to preprocess dla::CompiledGraph" / "Successfully open network but
#     cannot start execution" — DLA binary format is valid but runtime execution
#     preparation fails
#
# Ruling out:
#   A) APUSys DRAM exhaustion: 537 × ~6 KB = 3.2 MB << 18 MB limit
#   B) 0-byte fake-success DLA blobs: zero such entries in compile log
#
# Root cause hypothesis: v8_0_10 compiles specific BATCH_MATMUL DLAs using MDLA
# hardware features not available in device's adapter 8.2.26 (possibly MDLA 3.1
# instruction vs MDLA 3.0 hardware on Dimensity 9200 / MT6985).  The DLA "opens"
# successfully (format V230703 is recognized) but the internal graph revision step
# fails because the execution plan uses unsupported instructions.
#
# Diagnostic strategy: force ALL BATCH_MATMUL (type 86) ops to CPU.  If device
# loading succeeds → confirms BATCH_MATMUL is the root cause.  If still fails →
# one of ADD/CONCAT/SOFTMAX/MEAN/SUB/GREATER is the culprit.
#
# Note: BATCH_MATMUL on CPU means the transformer's core matrix multiply runs on
# CPU.  NPU will only execute ADD/CONCAT/SOFTMAX/MEAN/SUB/GREATER — minimal benefit.
# This attempt is DIAGNOSTIC only; if confirmed, Att56+ will explore partial
# BATCH_MATMUL (e.g. force only specific shapes to CPU).
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
EXPORT_WORK="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-shim-nobmm"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
OUTPUT_LITERTLM="$OUTPUT_DIR/gemma3-270m-att55.litertlm"
LOG_FILE="$ROOT/tmp/att55-no-batchmatmul.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTECT_SO=/tmp/protect_dla_dir.so

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 55: v8_0_10 + shim (BATCH_MATMUL also → CPU, diagnostic) ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2; exit 1
fi
MODEL="$EXPORT_WORK/model_quantized.tflite"
if [[ ! -f "$MODEL" ]]; then
  echo "ERROR: model_quantized.tflite not found" >&2; exit 1
fi
EMBEDDER="$EXPORT_WORK/embedder_quantized.tflite"
[[ ! -f "$EMBEDDER" ]] && EMBEDDER=""

# 1. Ensure import_forever=false patch
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"

# 2. protect_dla_dir.so
if [[ ! -f "$PROTECT_SO" ]]; then
  gcc -shared -fPIC -o "$PROTECT_SO" "$SCRIPT_DIR/protect_dla_dir.c" -ldl
fi

# 3. v8_0_10 SO + MapReshapeOps NOP
V8_SO=$(find "$VENV" -path '*/v8_0_10/host/lib/libneuron_adapter.so' 2>/dev/null | head -1)
[[ -z "$V8_SO" ]] && { echo "ERROR: v8_0_10 SO not found" >&2; exit 1; }
V8_DIR="$(dirname "$V8_SO")"
BAK="${V8_SO}.bak"
[[ -f "$BAK" ]] && cp "$BAK" "$V8_SO" || cp "$V8_SO" "$BAK"
. "$VENV/bin/activate"
"$VENV/bin/python" "$TOOLS_DIR/patch_v8_mapresha.py" "$V8_SO"

# 4. Build shim (BATCH_MATMUL now also forced to CPU)
REAL_SO="${V8_DIR}/libneuron_adapter_real.so"
cp "$V8_SO" "$REAL_SO"
ln -sf "libneuron_adapter_real.so" "${V8_DIR}/libneuron_adapter.so.8" 2>/dev/null || true

echo "Building shim (RESHAPE/FC/MUL/TRANSPOSE/BATCH_MATMUL → CPU)..."
gcc -shared -fPIC -o "$V8_SO" "$TOOLS_DIR/neuron_shim.c" \
  -L"$V8_DIR" \
  -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
  "-Wl,-rpath,\$ORIGIN" \
  -ldl -lpthread -O2 -Wall
echo "Shim: $V8_SO ($(du -sh "$V8_SO" | cut -f1))"

# 5. Compile
mkdir -p "$COMPILED_DIR"
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1 TF_NUM_INTRAOP_THREADS=1 TF_NUM_INTEROP_THREADS=1
export XLA_PYTHON_CLIENT_PREALLOCATE=false JAX_PLATFORMS=cpu JAX_PLATFORM_NAME=cpu
export CUDA_VISIBLE_DEVICES= MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib
export NEURON_ADAPTER_OVERRIDE_SO="$V8_SO" PROTECT_DLA_SO="$PROTECT_SO"

echo "=== Compiling prefill_decode ==="
rm -rf "$COMPILED_DIR/prefill_decode" "$COMPILED_DIR/prefill_decode.tflite"
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$MODEL" --output-dir "$COMPILED_DIR/prefill_decode" \
  --label prefill_decode --sdk-subdir v8_0_10/host/lib

COMPILED="$COMPILED_DIR/prefill_decode.tflite"
[[ -f "$COMPILED" && -s "$COMPILED" ]] || { echo "ERROR: prefill_decode missing" >&2; exit 1; }
echo "=== prefill_decode OK: $(du -sh "$COMPILED" | cut -f1) ==="

if [[ -n "$EMBEDDER" ]]; then
  echo "=== Compiling embedder (non-fatal) ==="
  rm -rf "$COMPILED_DIR/embedder" "$COMPILED_DIR/embedder.tflite"
  if nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
    --input "$EMBEDDER" --output-dir "$COMPILED_DIR/embedder" \
    --label embedder --sdk-subdir v8_0_10/host/lib 2>&1; then
    COMPILED_EMB="$COMPILED_DIR/embedder.tflite"
    [[ -f "$COMPILED_EMB" && -s "$COMPILED_EMB" ]] && \
      echo "=== embedder OK: $(du -sh "$COMPILED_EMB" | cut -f1) ===" || \
      echo "WARNING: embedder output empty — CPU fallback"
  else
    echo "WARNING: embedder compile failed — CPU fallback"
  fi
fi

# 6. Bundle
export HF_DATASETS_OFFLINE=1 TRANSFORMERS_OFFLINE=1
python "$SCRIPT_DIR/bundle_att49.py" \
  --export-work "$EXPORT_WORK" --compiled-dir "$COMPILED_DIR" \
  --output "$OUTPUT_LITERTLM" --model google/gemma-3-270m-it --cache-length 128

[[ -f "$OUTPUT_LITERTLM" && -s "$OUTPUT_LITERTLM" ]] || { echo "ERROR: bundle missing" >&2; exit 1; }
echo "=== Bundle OK: $OUTPUT_LITERTLM ($(du -sh "$OUTPUT_LITERTLM" | cut -f1)) ==="

echo "=== Check shim log: grep 'forced.*BMM' /tmp/neuron_shim_debug.log | tail ==="
grep 'forced.*BMM' /tmp/neuron_shim_debug.log 2>/dev/null | tail -5 || true
echo "=== DLA count: $(grep -c 'storeCompiledNetwork: real' /tmp/neuron_shim_debug.log 2>/dev/null || echo 0) ==="

echo "=== Att55 DONE ===" && date -Is
