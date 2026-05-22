#!/usr/bin/env bash
# Attempt 31: Re-run apply_plugin with import_forever=false patch.
# Restores SO from backup, applies the updated patch (explicit false instead
# of empty {}), then re-runs AOT compile on the cached model_quantized.tflite.
# Does NOT re-download or re-quantize (saves ~2 hours).
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever"
COMPILED_DIR="$OUTPUT_DIR/compiled_mt6985"
EXPORT_WORK="$OUTPUT_DIR/export_work"
LOG_FILE="$ROOT/tmp/att31-false-patch.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Attempt 31: import_forever=false patch ==="
date -Is

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv: $VENV" >&2
  exit 1
fi

if [[ ! -f "$EXPORT_WORK/model_quantized.tflite" ]]; then
  echo "ERROR: Cached model_quantized.tflite not found at $EXPORT_WORK/" >&2
  echo "Run the full export first." >&2
  exit 1
fi

SO_PATH=$(find "$VENV" -name "libLiteRtCompilerPlugin_MediaTek.so" 2>/dev/null | head -1)
if [[ -z "$SO_PATH" ]]; then
  echo "ERROR: libLiteRtCompilerPlugin_MediaTek.so not found in $VENV" >&2
  exit 1
fi

# Restore from backup (in case previous {} patch is still applied)
BAK="${SO_PATH}.bak"
if [[ -f "$BAK" ]]; then
  echo "Restoring SO from backup: $BAK"
  cp "$BAK" "$SO_PATH"
else
  echo "No backup found at $BAK — assuming SO is in original state"
fi
echo "SO state before patch:"
strings "$SO_PATH" | grep -E "import_forever|apusys-config" || true

# Apply the false patch
VENV="$VENV" bash "$SCRIPT_DIR/patch_no_import_forever.sh"
echo "SO state after patch:"
strings "$SO_PATH" | grep -E "import_forever|apusys-config" || true

# Clear the 0-byte compiled output
rm -rf "$COMPILED_DIR/prefill_decode"
rm -f "$COMPILED_DIR/prefill_decode.tflite"
mkdir -p "$COMPILED_DIR"

# Re-run just the apply_plugin step
. "$VENV/bin/activate"
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

echo "=== Running AOT compile on cached model_quantized.tflite ==="
nice -n 15 python "$SCRIPT_DIR/recompile_apply_plugin.py" \
  --input "$EXPORT_WORK/model_quantized.tflite" \
  --output-dir "$COMPILED_DIR/prefill_decode" \
  --label prefill_decode

COMPILED="$COMPILED_DIR/prefill_decode.tflite"
if [[ -f "$COMPILED" && -s "$COMPILED" ]]; then
  echo "=== AOT compile SUCCEEDED: $COMPILED ($(du -sh "$COMPILED" | cut -f1)) ==="
else
  echo "ERROR: compiled output missing or empty" >&2
  exit 1
fi
echo "Done."
date -Is
