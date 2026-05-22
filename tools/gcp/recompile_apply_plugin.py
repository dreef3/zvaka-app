#!/usr/bin/env python3
"""Re-run apply_plugin (AOT compile) on an already-quantized .tflite model.

Skips the full export+quantize pipeline. Useful when only the compiler plugin
SO has been patched and we want to re-attempt compilation without re-downloading
or re-quantizing the model.

Usage (activate venv first):
    python recompile_apply_plugin.py \
        --input ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever/export_work/model_quantized.tflite \
        --output-dir ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever/compiled_mt6985/prefill_decode \
        --label prefill_decode
"""
from __future__ import annotations
import argparse
import os
import pathlib
import sys

os.environ.setdefault("JAX_PLATFORMS", "cpu")
os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Path to quantized .tflite model")
    parser.add_argument("--output-dir", required=True, help="Directory for compiled output")
    parser.add_argument("--label", default="prefill_decode", help="Label for output file naming")
    parser.add_argument("--sdk-subdir", default="", help="Override MEDIATEK_SDK_LIB_SUBDIR")
    args = parser.parse_args()

    if args.sdk_subdir:
        os.environ["MEDIATEK_SDK_LIB_SUBDIR"] = args.sdk_subdir

    from ai_edge_litert.aot import aot_compile as aot_compile_lib
    from ai_edge_litert.aot.aot_compile import aot_types
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target

    input_path = pathlib.Path(args.input).resolve()
    output_dir = pathlib.Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Input:      {input_path}  ({input_path.stat().st_size / 1e6:.1f} MB)")
    print(f"Output dir: {output_dir}")
    print(f"Label:      {args.label}")

    if not input_path.exists():
        print(f"ERROR: input file not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    target = mediatek_target.Target(mediatek_target.SocModel.MT6985)
    print(f"Target: {target}")

    # Inject LD_PRELOAD so the apply_plugin_main subprocess loads the
    # DLA-dir protector shim (interceptors rmdir/unlink to recreate the
    # directory after the compiler plugin prematurely deletes it).
    protect_so = os.environ.get("PROTECT_DLA_SO", "")
    if protect_so and os.path.exists(protect_so):
        existing = os.environ.get("LD_PRELOAD", "")
        os.environ["LD_PRELOAD"] = f"{protect_so}:{existing}" if existing else protect_so
        print(f"LD_PRELOAD injected: {os.environ['LD_PRELOAD']}")
    else:
        print(f"Note: PROTECT_DLA_SO not set or missing, DLA dir protection inactive")

    print("Running aot_compile...")
    result = aot_compile_lib.aot_compile(
        aot_types.Model.create_from_path(input_path),
        output_dir=str(output_dir),
        target=target,
    )

    if result.failed_backends:
        failures = ", ".join(
            f"{backend.target_id}: {error}"
            for backend, error in result.failed_backends
        )
        print(f"ERROR: AOT compilation FAILED: {failures}", file=sys.stderr)
        sys.exit(1)

    if len(result.models_with_backend) != 1:
        print(
            f"ERROR: Expected 1 compiled model, got {len(result.models_with_backend)}",
            file=sys.stderr,
        )
        sys.exit(1)

    compiled_model = result.models_with_backend[0][1]
    compiled_path = output_dir.parent / f"{args.label}.tflite"
    compiled_model.save(compiled_path, export_only=True)
    print(f"SUCCESS: compiled model saved to {compiled_path}  ({compiled_path.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
