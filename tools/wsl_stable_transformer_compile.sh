#!/usr/bin/env bash
set -euo pipefail

cd /home/ae/src/litert-build
. .venv-litert-214/bin/activate

EXPORT_ROOT=${1:-/home/ae/src/litert-build/tmp/gemma4-mt6985-build-512-stable214}
COMPILE_ROOT=${2:-/home/ae/src/litert-build/tmp/stable214-transformer}
export EXPORT_ROOT COMPILE_ROOT

python - <<'PY'
import pathlib
import os

from ai_edge_quantizer import quantizer as quantizer_lib
from ai_edge_quantizer import recipe as recipe_lib
from ai_edge_litert.aot import aot_compile as aot_compile_lib
from ai_edge_litert.aot.aot_compile import aot_types
from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target

root = pathlib.Path(os.environ['EXPORT_ROOT']) / 'export_work'
out_root = pathlib.Path(os.environ['COMPILE_ROOT'])
out_root.mkdir(parents=True, exist_ok=True)

src = root / 'model.tflite'
quant = out_root / 'model_weight_only_wi4_afp32.tflite'

qt = quantizer_lib.Quantizer(str(src))
qt.load_quantization_recipe(recipe_lib.weight_only_wi4_afp32())
qt.quantize().export_model(str(quant), overwrite=True)

result = aot_compile_lib.aot_compile(
    aot_types.Model.create_from_path(quant),
    output_dir=str(out_root / 'model_weight_only_wi4_afp32'),
    target=mediatek_target.Target(mediatek_target.SocModel.MT6985),
)

if result.failed_backends:
    raise SystemExit(str(result.failed_backends))

print(out_root / 'model_weight_only_wi4_afp32' / 'model_weight_only_wi4_afp32_MediaTek_MT6985_apply_plugin.tflite')
PY
