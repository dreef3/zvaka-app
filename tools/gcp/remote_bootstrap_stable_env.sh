#!/usr/bin/env bash
set -euo pipefail

ROOT=${ROOT:-$HOME/src/litert-build}
TORCH_ROOT=${TORCH_ROOT:-$HOME/src/litert-torch}
VENV=${VENV:-$ROOT/.venv-litert-214}

sudo apt-get update
sudo apt-get install -y python3.12-venv python3-pip git-lfs curl

if ! command -v uv >/dev/null 2>&1; then
  curl -LsSf https://astral.sh/uv/install.sh | sh
fi

export PATH="$HOME/.local/bin:$PATH"
mkdir -p "$ROOT/.cache/uv-214"
export UV_CACHE_DIR="$ROOT/.cache/uv-214"

uv venv --clear --python python3.12 "$VENV"

uv pip install --python "$VENV/bin/python" --force-reinstall \
  ai-edge-litert==2.1.4 \
  ai-edge-quantizer==0.6.0 \
  litert-converter==0.1.0 \
  ai-edge-litert-sdk-mediatek==0.2.0

uv pip install --python "$VENV/bin/python" \
  --index-url https://download.pytorch.org/whl/cpu \
  torch==2.11.0+cpu torchvision==0.26.0+cpu torchaudio==2.11.0+cpu

uv pip install --python "$VENV/bin/python" \
  absl-py scipy numpy tabulate safetensors kagglehub transformers \
  multipledispatch jaxtyping fire rich torchao 'jax[cpu]' accelerate \
  sentencepiece

uv pip install --python "$VENV/bin/python" --no-deps -e "$TORCH_ROOT"

"$VENV/bin/python" - <<'PY'
import importlib.metadata as m
for pkg in [
    'ai-edge-litert',
    'ai-edge-quantizer',
    'litert-converter',
    'ai-edge-litert-sdk-mediatek',
    'litert-torch',
]:
    print(pkg, m.version(pkg))
PY
