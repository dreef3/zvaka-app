#!/usr/bin/env python3
# pyright: reportMissingImports=false

"""Build an MT6985-targeted LiteRT-LM bundle for a public HF model.

This helper follows the public export path we validated:
1. Export HF artifacts with the right LiteRT-LM submodels for the model family.
2. AOT-compile exported submodels for MediaTek `MT6985`.
3. Rebuild a `.litertlm` bundle whose text submodels are constrained to `npu`.

The script expects local source checkouts for `litert-torch` and `LiteRT-LM` and a
Python environment with the corresponding dependencies installed.
"""

from __future__ import annotations

import argparse
import gc
import os
import pathlib
import sys
import tempfile


os.environ.setdefault("JAX_PLATFORMS", "cpu")
os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")


def _add_python_path(path: pathlib.Path) -> None:
    resolved = str(path.resolve())
    if resolved not in sys.path:
        sys.path.insert(0, resolved)


def _build_parser() -> argparse.ArgumentParser:
    default_src_root = pathlib.Path.home() / "src"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model", required=True, help="HF model id or local safetensors path"
    )
    parser.add_argument(
        "--output-dir", required=True, help="Directory for exported artifacts"
    )
    parser.add_argument(
        "--litert-torch-root",
        default=str(default_src_root / "litert-torch"),
        help="Path to the litert-torch checkout",
    )
    parser.add_argument(
        "--litert-lm-root",
        default=str(default_src_root / "LiteRT-LM"),
        help="Path to the LiteRT-LM checkout",
    )
    parser.add_argument(
        "--prefill-lengths",
        default="128",
        help="Comma-separated prefill lengths. NPU runtime currently expects 128.",
    )
    parser.add_argument(
        "--cache-length",
        type=int,
        default=1280,
        help="KV cache length to export",
    )
    parser.add_argument(
        "--quantization-recipe",
        default="dynamic_wi4_afp32",
        help="Quantization recipe passed to litert-torch export",
    )
    parser.add_argument(
        "--model-family",
        choices=["gemma3", "gemma4"],
        default="gemma4",
        help="Selects the export configuration shape",
    )
    parser.add_argument(
        "--trust-remote-code",
        action="store_true",
        help="Pass trust_remote_code through to the HF exporter",
    )
    parser.add_argument(
        "--keep-intermediates",
        action="store_true",
        help="Keep the temporary export workspace",
    )
    return parser


def _parse_prefill_lengths(value: str) -> list[int]:
    lengths = [int(item.strip()) for item in value.split(",") if item.strip()]
    if not lengths:
        raise ValueError("At least one prefill length is required")
    return lengths


def _pick_single_compiled_model(compilation_result, expected_label: str):
    if compilation_result.failed_backends:
        failures = ", ".join(
            f"{backend.target_id}: {error}"
            for backend, error in compilation_result.failed_backends
        )
        raise RuntimeError(f"AOT compilation failed for {expected_label}: {failures}")
    if len(compilation_result.models_with_backend) != 1:
        raise RuntimeError(
            f"Expected exactly one compiled model for {expected_label}, got "
            f"{len(compilation_result.models_with_backend)}"
        )
    return compilation_result.models_with_backend[0][1]


def _resolve_model_load_dtype(torch_module, config, model_family: str):
    if model_family != "gemma4":
        return torch_module.float32

    for value in (
        getattr(config, "dtype", None),
        getattr(getattr(config, "text_config", None), "dtype", None),
    ):
        if isinstance(value, str):
            dtype = getattr(torch_module, value, None)
            if dtype is not None:
                return dtype
        elif value is not None:
            return value

    return torch_module.bfloat16


def _prune_unused_modules(model_artifact, model_family: str) -> None:
    if model_family != "gemma4":
        return

    model_root = getattr(model_artifact, "model", None)
    if model_root is None:
        return

    for attr in ("vision_tower", "audio_tower", "multimodal_embedder"):
        if hasattr(model_root, attr):
            setattr(model_root, attr, None)

    gc.collect()


def _load_source_model(
    export_lib, model: str, trust_remote_code: bool, task, model_family: str
):
    if model_family != "gemma4":
        return export_lib.load_model(
            model,
            trust_remote_code=trust_remote_code,
            task=task,
        )

    import json
    import os

    import huggingface_hub
    import torch
    import transformers

    from litert_torch.generative.export_hf.model_ext import patches as model_ext_patches

    config = transformers.AutoConfig.from_pretrained(
        model,
        dtype=torch.bfloat16,
        trust_remote_code=trust_remote_code,
    )
    config._attn_implementation = "lrt_transposed_attention"  # pylint: disable=protected-access
    model_load_dtype = _resolve_model_load_dtype(torch, config, model_family)

    if task == export_lib.ExportTask.TEXT_GENERATION:
        auto_model_cls = transformers.AutoModelForCausalLM
    elif task == export_lib.ExportTask.IMAGE_TEXT_TO_TEXT:
        auto_model_cls = transformers.AutoModelForImageTextToText
    else:
        raise ValueError(f"Unsupported task: {task}")

    # Gemma 4 does not fit in this WSL devcontainer when loaded in float32.
    with model_ext_patches.get_patch_context(config.model_type):
        model_artifact = auto_model_cls.from_pretrained(
            model,
            config=config,
            torch_dtype=model_load_dtype,
            low_cpu_mem_usage=True,
            trust_remote_code=trust_remote_code,
        )

    _prune_unused_modules(model_artifact, model_family)

    if task == export_lib.ExportTask.TEXT_GENERATION:
        model_artifact.generation_config.cache_implementation = "static"
        model_artifact.generation_config.do_sample = False

    text_model_config = getattr(config, "text_config", config)
    if task == export_lib.ExportTask.TEXT_GENERATION:
        export_lib.verify_model_compatibility(
            model_artifact,
            config,
            text_model_config,
        )

    if task == export_lib.ExportTask.IMAGE_TEXT_TO_TEXT:
        image_processor = transformers.AutoImageProcessor.from_pretrained(model)
    else:
        image_processor = None

    tokenizer = transformers.AutoTokenizer.from_pretrained(model)
    if not hasattr(tokenizer, "chat_template") or not tokenizer.chat_template:
        try:
            if export_lib.utils.get_model_path_type(model) == "repo_id":
                template_file = huggingface_hub.hf_hub_download(
                    model,
                    filename="chat_template.json",
                )
            else:
                template_file = os.path.join(model, "chat_template.json")
            with open(template_file, "rt") as f:
                chat_template_dict = json.load(f)
            if "chat_template" in chat_template_dict:
                tokenizer.chat_template = chat_template_dict["chat_template"]
        except Exception as exc:  # pylint: disable=broad-exception-caught
            print(f"Failed to load chat template: {exc}")

    return export_lib.SourceModelArtifacts(
        model=model_artifact,
        model_config=config,
        text_model_config=text_model_config,
        tokenizer=tokenizer,
        image_processor=image_processor,
    )


def main() -> int:
    args = _build_parser().parse_args()

    output_dir = pathlib.Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    litert_torch_root = pathlib.Path(args.litert_torch_root).resolve()
    _add_python_path(litert_torch_root)

    from litert_torch.generative.export_hf.core import export_lib
    from litert_torch.generative.export_hf.core import exportable_module
    from litert_torch.generative.export_hf.core import (
        litert_lm_builder as export_builder,
    )

    from ai_edge_litert.aot import aot_compile as aot_compile_lib
    from ai_edge_litert.aot.aot_compile import aot_types
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target
    from ai_edge_litert.internal import litertlm_builder

    prefill_lengths = _parse_prefill_lengths(args.prefill_lengths)

    temp_dir_obj = None
    if args.keep_intermediates:
        export_work_dir = output_dir / "export_work"
        export_work_dir.mkdir(parents=True, exist_ok=True)
    else:
        temp_dir_obj = tempfile.TemporaryDirectory(
            prefix="litertlm-mt6985-", dir=output_dir
        )
        export_work_dir = pathlib.Path(temp_dir_obj.name)

    export_output_dir = output_dir / "generic_export"
    export_output_dir.mkdir(parents=True, exist_ok=True)

    if args.model_family == "gemma4":
        # Prefer lazy resource constants aggressively to keep MLIR lowering below
        # the memory limit on commodity hosts.
        os.environ.setdefault("RESOURCE_CONSTANT_NUMEL_THRESHOLD", "1")

    export_config = exportable_module.ExportableModuleConfig(
        model=args.model,
        output_dir=str(export_output_dir),
        work_dir=str(export_work_dir),
        trust_remote_code=args.trust_remote_code,
        prefill_lengths=prefill_lengths,
        cache_length=args.cache_length,
        quantization_recipe=args.quantization_recipe,
        split_cache=args.model_family == "gemma3",
        externalize_embedder=args.model_family == "gemma4",
        bundle_litert_lm=False,
        experimental_lightweight_conversion=args.model_family == "gemma4",
    )

    source_model_artifacts = _load_source_model(
        export_lib,
        args.model,
        args.trust_remote_code,
        export_config.task,
        args.model_family,
    )
    export_config = export_lib.update_export_config(
        export_config, source_model_artifacts
    )

    exported = export_lib.ExportedModelArtifacts()
    for task_fn in (
        export_lib.export_text_prefill_decode_model,
        export_lib.export_embedder_model,
        export_lib.export_auxiliary_model,
        export_lib.export_additional_models,
        export_lib.export_tokenizer,
    ):
        exported = task_fn(source_model_artifacts, export_config, exported)

    target = mediatek_target.Target(mediatek_target.SocModel.MT6985)

    compiled_dir = output_dir / "compiled_mt6985"
    compiled_dir.mkdir(parents=True, exist_ok=True)

    def compile_model(input_path: str, label: str) -> pathlib.Path:
        if not input_path:
            raise FileNotFoundError(f"Missing exported model path for {label}")
        result = aot_compile_lib.aot_compile(
            aot_types.Model.create_from_path(pathlib.Path(input_path)),
            output_dir=str(compiled_dir / label),
            target=target,
        )
        compiled_model = _pick_single_compiled_model(result, label)
        compiled_path = compiled_dir / f"{label}.tflite"
        compiled_model.save(compiled_path, export_only=True)
        return compiled_path

    compiled_prefill_decode = compile_model(
        exported.prefill_decode_model_path, "prefill_decode"
    )
    compiled_embedder = None
    if exported.embedder_model_path:
        compiled_embedder = compile_model(exported.embedder_model_path, "embedder")
    compiled_auxiliary = None
    if exported.auxiliary_model_path:
        compiled_auxiliary = compile_model(exported.auxiliary_model_path, "aux")
    compiled_per_layer_embedder = None
    additional_model_paths = exported.additional_model_paths or {}
    if additional_model_paths.get("per_layer_embedder"):
        compiled_per_layer_embedder = compile_model(
            additional_model_paths["per_layer_embedder"],
            "per_layer_embedder",
        )

    llm_metadata_path = export_work_dir / "llm_metadata.pb"
    if not llm_metadata_path.exists():
        exported = export_builder.package_model(
            source_model_artifacts, export_config, exported
        )
        if not llm_metadata_path.exists():
            raise FileNotFoundError(f"Missing llm metadata at {llm_metadata_path}")

    output_name = pathlib.Path(args.model.rstrip("/")).name.replace("/", "-")
    final_bundle_path = output_dir / f"{output_name}_mt6985.litertlm"
    builder = litertlm_builder.LitertLmFileBuilder()
    builder.add_system_metadata(
        litertlm_builder.Metadata(
            key="Authors",
            value="ODML",
            dtype=litertlm_builder.DType.STRING,
        )
    )
    builder.add_llm_metadata(str(llm_metadata_path))
    if exported.tokenizer_model_path is None:
        raise FileNotFoundError("Tokenizer export did not produce a tokenizer path")
    if exported.tokenizer_model_path.endswith(".json"):
        builder.add_hf_tokenizer(exported.tokenizer_model_path)
    else:
        builder.add_sentencepiece_tokenizer(exported.tokenizer_model_path)

    builder.add_tflite_model(
        str(compiled_prefill_decode),
        litertlm_builder.TfLiteModelType.PREFILL_DECODE,
        backend_constraint="npu",
    )
    if compiled_embedder is not None:
        builder.add_tflite_model(
            str(compiled_embedder),
            litertlm_builder.TfLiteModelType.EMBEDDER,
            backend_constraint="npu",
        )
    if compiled_auxiliary is not None:
        builder.add_tflite_model(
            str(compiled_auxiliary),
            litertlm_builder.TfLiteModelType.AUX,
            backend_constraint="npu",
        )
    if compiled_per_layer_embedder is not None:
        builder.add_tflite_model(
            str(compiled_per_layer_embedder),
            litertlm_builder.TfLiteModelType.PER_LAYER_EMBEDDER,
            backend_constraint="npu",
        )

    with open(final_bundle_path, "wb") as f:
        builder.build(f)

    print(final_bundle_path)

    if temp_dir_obj is not None:
        temp_dir_obj.cleanup()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
