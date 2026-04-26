#!/usr/bin/env bash
set -euo pipefail

rm -rf .venv-litert

WORK_ROOT=${WORK_ROOT:-$(pwd)}
SRC_ROOT=${SRC_ROOT:-$HOME/src}
UV_BIN=${UV_BIN:-uv}

mkdir -p "$WORK_ROOT/.cache/uv" "$WORK_ROOT/.tmp"
export UV_CACHE_DIR="$WORK_ROOT/.cache/uv"
export TMPDIR="$WORK_ROOT/.tmp"
export WORK_ROOT SRC_ROOT UV_CACHE_DIR

PYTHON_BIN=${PYTHON_BIN:-python3.12}
PYTHON_SPEC="$PYTHON_BIN"

if ! command -v "$UV_BIN" >/dev/null 2>&1; then
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_SPEC=${PYTHON_BIN#python}
  "$UV_BIN" python install "$PYTHON_SPEC"
fi

"$UV_BIN" venv --python "$PYTHON_SPEC" .venv-litert
UV_PYTHON="$WORK_ROOT/.venv-litert/bin/python"

"$UV_BIN" pip install --python "$UV_PYTHON" --upgrade pip setuptools wheel

"$UV_BIN" pip install --python "$UV_PYTHON" \
  --index-url https://download.pytorch.org/whl/cpu \
  torch==2.11.0+cpu torchvision==0.26.0+cpu torchaudio==2.11.0+cpu

tmp_requirements=$(mktemp "$WORK_ROOT/.tmp/litert-reqs.XXXXXX")
grep -vE '^(torch==|torchvision==|torchaudio==)' \
  "$SRC_ROOT/litert-torch/requirements.txt" > "$tmp_requirements"
"$UV_BIN" pip install --python "$UV_PYTHON" --pre -r "$tmp_requirements"
"$UV_BIN" pip install --python "$UV_PYTHON" accelerate
rm -f "$tmp_requirements"

sdk_package_path="$SRC_ROOT/model-artifacts/ai_edge_litert_sdk_mediatek-0.2.0.tar.gz"
if [[ -f "$sdk_package_path" ]]; then
  SKIP_SDK_DOWNLOAD=0 "$UV_BIN" pip install --python "$UV_PYTHON" \
    "$sdk_package_path"
else
  SKIP_SDK_DOWNLOAD=0 "$UV_BIN" pip install --python "$UV_PYTHON" \
    ai-edge-litert-sdk-mediatek==0.2.0
fi

"$UV_PYTHON" - <<'PY'
import os
import pathlib
import tarfile
import urllib.request

import ai_edge_litert_sdk_mediatek

sdk_package_dir = pathlib.Path(ai_edge_litert_sdk_mediatek.__file__).resolve().parent
sdk_data_dir = sdk_package_dir / "data"
if not sdk_data_dir.exists():
    sdk_data_dir.mkdir(parents=True, exist_ok=True)
    archive_path = pathlib.Path(os.environ["WORK_ROOT"]) / ".tmp/mediatek_sdk.tar.gz"
    urllib.request.urlretrieve(
        "https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz",
        archive_path,
    )
    with tarfile.open(archive_path, "r:gz") as tar:
        members = []
        for member in tar.getmembers():
            prefix = "neuro_pilot/"
            if not member.name.startswith(prefix) or member.name == prefix:
                continue
            member_copy = tar.getmember(member.name)
            member_copy.name = member.name[len(prefix):]
            members.append(member_copy)
        tar.extractall(path=sdk_data_dir, members=members)
    archive_path.unlink(missing_ok=True)
    if not any(sdk_data_dir.iterdir()):
        raise SystemExit("MediaTek SDK extraction produced no files")
PY

"$UV_BIN" pip install --python "$UV_PYTHON" -e "$SRC_ROOT/litert-torch"

"$UV_PYTHON" - <<'PY'
import pathlib

import ai_edge_litert_sdk_mediatek

sdk_root = ai_edge_litert_sdk_mediatek.get_sdk_path()
print(f"MediaTek SDK root: {sdk_root}")
if sdk_root is None or not pathlib.Path(sdk_root).exists():
    raise SystemExit("MediaTek SDK download/install failed")
PY
