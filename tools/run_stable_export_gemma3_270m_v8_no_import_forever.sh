set -euo pipefail
# Export Gemma3-270M for MT6985 using v8_0_10 SDK (device-compatible DLA) with
# import_forever=false. Requires patched v8_0_10 libneuron_adapter.so
# (patch_v8_mapresha.py) to skip the MapReshapeOps input-count check that
# incorrectly rejects RESHAPE ops with a shape tensor present.

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert-214"
OUTPUT_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever"
LOG_FILE="$ROOT/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever.log"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing stable export environment: $VENV" >&2
  echo "Expected Python at: $VENV/bin/python" >&2
  exit 1
fi

mkdir -p "$ROOT/tmp"
rm -rf "$OUTPUT_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "=== Gemma 3 270M MT6985 v8_0_10 SDK export (no import_forever) ==="
date -Is
echo "Output dir: $OUTPUT_DIR"
echo "Log file: $LOG_FILE"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Patch import_forever=true → false in the compiler plugin SO
VENV="$VENV" bash "$SCRIPT_DIR/gcp/patch_no_import_forever.sh"

# 2. Patch v8_0_10 libneuron_adapter.so to skip MapReshapeOps input-count check
V8_SO=$(find "$VENV" -path "*/v8_0_10/host/lib/libneuron_adapter.so" 2>/dev/null | head -1)
if [[ -z "$V8_SO" ]]; then
  echo "ERROR: v8_0_10 libneuron_adapter.so not found in $VENV" >&2
  exit 1
fi
echo "Applying MapReshapeOps patch to: $V8_SO"
"$VENV/bin/python" "$SCRIPT_DIR/patch_v8_mapresha.py" "$V8_SO"

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
export RESOURCE_CONSTANT_NUMEL_THRESHOLD=1
# No MEDIATEK_SDK_LIB_SUBDIR override → uses default v8_0_10

cd "$ROOT"
. "$VENV/bin/activate"

nice -n 15 python tools/build_mt6985_gemma3_litertlm.py \
  --model google/gemma-3-270m-it \
  --model-family gemma3 \
  --cache-length 128 \
  --prefill-lengths 128 \
  --output-dir "$OUTPUT_DIR" \
  --keep-intermediates
