#!/usr/bin/env bash
set -euo pipefail

cd /home/ae/src/litert-build
. .venv-litert-214/bin/activate

EXPORT_ROOT=${1:-/home/ae/src/litert-build/tmp/gemma4-mt6985-build-128-stable214}
COMPILE_ROOT=${2:-/home/ae/src/litert-build/tmp/stable214-transformer-raw}
export EXPORT_ROOT COMPILE_ROOT

python - <<'PY'
import os
import pathlib
import shutil

from ai_edge_litert.aot import aot_compile as aot_compile_lib
from ai_edge_litert.aot.aot_compile import aot_types
from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target

src = pathlib.Path(os.environ['EXPORT_ROOT']) / 'export_work' / 'model.tflite'
out_root = pathlib.Path(os.environ['COMPILE_ROOT'])
out_root.mkdir(parents=True, exist_ok=True)

result = aot_compile_lib.aot_compile(
    aot_types.Model.create_from_path(src),
    output_dir=str(out_root),
    target=mediatek_target.Target(mediatek_target.SocModel.MT6985),
)

if result.failed_backends:
    error_files = sorted(pathlib.Path('/tmp').glob('tmp*.error'), key=lambda p: p.stat().st_mtime, reverse=True)
    if error_files:
        shutil.copy(error_files[0], out_root / 'latest_apply_plugin.error')
    raise SystemExit(str(result.failed_backends))

print(out_root)
PY
