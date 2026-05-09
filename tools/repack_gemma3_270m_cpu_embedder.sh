#!/usr/bin/env bash
# Repack gemma-3-270m-it_mt6985.litertlm with the embedder running on CPU.
#
# Why: the v2 build packaged the embedder with backend_constraint="npu", which
# causes "Tensor strides are not supported" in dispatch_api.cc:271 on MT6985 when
# the NPU dispatch delegate tries to register an embedding lookup tensor.
#
# Fix: use the quantized-only embedder TFLite (no DLA compile) with
# backend_constraint="cpu". The prefill/decode DLA model is unchanged.
#
# This script reuses ALL intermediates from the v2 build — no re-export or
# re-compile needed. Runs in seconds.
set -euo pipefail

ROOT=/home/ae/src/litert-build
VENV="$ROOT/.venv-litert"
BUILD_DIR="$ROOT/tmp/gemma3-270m-mt6985-p128-c128"
COMPILED_DIR="$BUILD_DIR/compiled_mt6985"
EXPORT_DIR="$BUILD_DIR/export_work"
OUTPUT="$BUILD_DIR/gemma-3-270m-it_mt6985_v3.litertlm"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "ERROR: Missing venv at $VENV" >&2
  exit 1
fi

if [[ ! -d "$COMPILED_DIR" ]]; then
  echo "ERROR: v2 build intermediates not found at $COMPILED_DIR" >&2
  echo "Run run_nightly_export_gemma3_270m_p128_c128.sh first." >&2
  exit 1
fi

# Required files from v2 build
PREFILL_DECODE="$COMPILED_DIR/prefill_decode.tflite"
# Quantized embedder (no DLA compile) — created as intermediate by compile_model
# Either weight_only_wi4_afp32 (gemma3 v2 compile recipe) or dynamic_wi4_afp32
EMBEDDER_QUANTIZED=""
for recipe in weight_only_wi4_afp32 dynamic_wi4_afp32; do
  candidate="$COMPILED_DIR/embedder_${recipe}.tflite"
  if [[ -f "$candidate" ]]; then
    EMBEDDER_QUANTIZED="$candidate"
    echo "Using quantized embedder: $candidate"
    break
  fi
done
if [[ -z "$EMBEDDER_QUANTIZED" ]]; then
  echo "ERROR: No quantized embedder found in $COMPILED_DIR" >&2
  echo "Expected: embedder_weight_only_wi4_afp32.tflite or embedder_dynamic_wi4_afp32.tflite" >&2
  exit 1
fi

AUXILIARY="$COMPILED_DIR/aux.tflite"
LLM_METADATA="$EXPORT_DIR/llm_metadata.pb"
TOKENIZER_JSON="$EXPORT_DIR/tokenizer.json"

for f in "$PREFILL_DECODE" "$LLM_METADATA"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: Required file missing: $f" >&2
    exit 1
  fi
done

export MEDIATEK_SDK_LIB_SUBDIR=v8_0_10/host/lib

cd "$ROOT"
. "$VENV/bin/activate"

echo "Repacking litertlm with CPU embedder..."
echo "  prefill_decode (DLA/NPU): $PREFILL_DECODE"
echo "  embedder (CPU only):      $EMBEDDER_QUANTIZED"
echo "  output:                   $OUTPUT"
date -Is

python - <<'PYEOF'
import sys
import pathlib
import os

root = pathlib.Path("/home/ae/src/litert-build")
build_dir = root / "tmp/gemma3-270m-mt6985-p128-c128"
compiled_dir = build_dir / "compiled_mt6985"
export_dir = build_dir / "export_work"
output = build_dir / "gemma-3-270m-it_mt6985_v3.litertlm"

# Find quantized (non-DLA) embedder
embedder_cpu = None
for recipe in ["weight_only_wi4_afp32", "dynamic_wi4_afp32"]:
    candidate = compiled_dir / f"embedder_{recipe}.tflite"
    if candidate.exists():
        embedder_cpu = candidate
        break
if embedder_cpu is None:
    print("ERROR: No quantized embedder found", file=sys.stderr)
    sys.exit(1)

# Load litertlm_builder from the venv
from ai_edge_litert.internal import litertlm_builder

b = litertlm_builder.LitertLmFileBuilder()
# Note: Metadata/DType may or may not be available depending on version
try:
    b.add_system_metadata(
        litertlm_builder.Metadata(
            key="Authors",
            value="ODML",
            dtype=litertlm_builder.DType.STRING,
        )
    )
except AttributeError:
    pass  # Older versions may not have Metadata class; skip it

llm_metadata = export_dir / "llm_metadata.pb"
tokenizer_json = export_dir / "tokenizer.json"
tokenizer_spm = export_dir / "tokenizer.model"

b.add_llm_metadata(str(llm_metadata))
if tokenizer_json.exists():
    b.add_hf_tokenizer(str(tokenizer_json))
elif tokenizer_spm.exists():
    b.add_sentencepiece_tokenizer(str(tokenizer_spm))
else:
    print("ERROR: No tokenizer found", file=sys.stderr)
    sys.exit(1)

# Main prefill/decode model — DLA/NPU as before
prefill_decode = compiled_dir / "prefill_decode.tflite"
b.add_tflite_model(
    str(prefill_decode),
    litertlm_builder.TfLiteModelType.PREFILL_DECODE,
    backend_constraint="npu",
)

# Embedder — CPU only (quantized TFLite, no DispatchDelegate)
b.add_tflite_model(
    str(embedder_cpu),
    litertlm_builder.TfLiteModelType.EMBEDDER,
    backend_constraint="cpu",
)

# Auxiliary model (sampler, etc.) — CPU as before
# aux is NOT DLA-compiled; it lives in export_work/auxiliary.tflite
aux = export_dir / "auxiliary.tflite"
if aux.exists():
    b.add_tflite_model(
        str(aux),
        litertlm_builder.TfLiteModelType.AUX,
    )

with open(output, "wb") as f:
    b.build(f)

size_mb = output.stat().st_size / 1_000_000
print(f"Done: {output} ({size_mb:.1f} MB)")
PYEOF

echo "Repack complete."
ls -lh "$OUTPUT"
