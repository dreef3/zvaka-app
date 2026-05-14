#!/usr/bin/env python3
"""
aot_compile_gemma3_v9.py - Run only the AOT compilation step for Gemma3 270M.

Skips the slow HuggingFace export; takes pre-built model_quantized.tflite and
compiles it against the v9_0_3 MediaTek SDK for MT6985.

Usage:
  python3 aot_compile_gemma3_v9.py \\
    --input  /path/to/model_quantized.tflite \\
    --output /path/to/compiled_mt6985/ \\
    [--sdk-subdir v9_0_3/host/lib]
"""

import argparse
import os
import pathlib
import sys
import tempfile


def _add_python_path(root: pathlib.Path):
    for p in [root, root / "src"]:
        s = str(p)
        if s not in sys.path:
            sys.path.insert(0, s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="Input .tflite model")
    ap.add_argument("--output-dir", required=True, help="Output directory for compiled model")
    ap.add_argument("--sdk-subdir", default="v9_0_3/host/lib")
    ap.add_argument("--litert-torch-root", default=os.path.expanduser("~/src/litert-torch"))
    args = ap.parse_args()

    _add_python_path(pathlib.Path(args.litert_torch_root))

    import ai_edge_litert_sdk_mediatek
    from ai_edge_litert.aot import aot_compile as aot_compile_lib
    from ai_edge_litert.aot.aot_compile import aot_types
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target

    # Override SDK lib path
    sdk_root = ai_edge_litert_sdk_mediatek.get_sdk_path()
    resolved = (sdk_root / args.sdk_subdir).resolve()
    if not resolved.exists():
        print(f"ERROR: SDK lib path not found: {resolved}", file=sys.stderr)
        sys.exit(1)

    def path_to_sdk_libs(version="v8"):
        return resolved

    ai_edge_litert_sdk_mediatek.path_to_sdk_libs = path_to_sdk_libs
    print(f"SDK libs: {resolved}", file=sys.stderr)

    # Pre-create a temp dir for MTKNN_ADAPTER_DLA_DIR so the v9 neuron_adapter
    # doesn't fail with "User provided MTKNN_ADAPTER_DLA_DIR is not a valid directory".
    _dla_dir = tempfile.mkdtemp(prefix="mtknn_dla_")
    os.environ["MTKNN_ADAPTER_DLA_DIR"] = _dla_dir
    print(f"MTKNN_ADAPTER_DLA_DIR: {_dla_dir}", file=sys.stderr)

    input_path = pathlib.Path(args.input).resolve()
    output_dir = pathlib.Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Compiling: {input_path}", file=sys.stderr)
    print(f"Output:    {output_dir}", file=sys.stderr)

    target = mediatek_target.Target(mediatek_target.SocModel.MT6985)

    result = aot_compile_lib.aot_compile(
        aot_types.Model.create_from_path(input_path),
        output_dir=str(output_dir),
        target=target,
    )

    if result.failed_backends:
        for backend, error in result.failed_backends:
            print(f"FAILED {backend.target_id}: {error}", file=sys.stderr)
        sys.exit(1)

    if not result.models_with_backend:
        print("ERROR: No compiled models produced (0 NPU ops?)", file=sys.stderr)
        # Still write whatever came out for inspection
        sys.exit(2)

    for backend, model in result.models_with_backend:
        out_path = output_dir / f"compiled_{backend.target_id}.tflite"
        model.save(out_path, export_only=True)
        # Check output size
        sz = out_path.stat().st_size
        print(f"OK {backend.target_id}: {out_path}  ({sz:,} bytes)")
        if sz == 0:
            print("WARNING: compiled output is 0 bytes — no NPU ops selected", file=sys.stderr)

    print("Done.")


if __name__ == "__main__":
    main()
