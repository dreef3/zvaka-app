#!/usr/bin/env python3
"""
bundle_att49.py — Assemble Att49 compiled Gemma3-270M artifacts into .litertlm.

Att49 compiles the ORIGINAL model_quantized.tflite (no NS-patcher passes), so
no _restore_fc_bias or _fix_pack_scalar_inputs post-processing is needed.

Usage:
  python3 bundle_att49.py \
    --export-work  ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v8-no-import-forever/export_work \
    --compiled-dir ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever/compiled_mt6985 \
    --output       ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9-no-import-forever/gemma3-270m-att49.litertlm \
    [--model google/gemma-3-270m-it] \
    [--cache-length 128]
"""
from __future__ import annotations

import argparse
import os
import pathlib
import sys

os.environ.setdefault("JAX_PLATFORMS", "cpu")
os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")


def _build_llm_metadata_proto(model_id: str, cache_length: int, chat_template_path: pathlib.Path | None):
    from transformers import AutoTokenizer, GenerationConfig
    from ai_edge_litert.internal import llm_metadata_pb2, llm_model_type_pb2

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    try:
        gen_config = GenerationConfig.from_pretrained(model_id)
    except Exception:
        gen_config = None

    llm_metadata = llm_metadata_pb2.LlmMetadata()

    if hasattr(tokenizer, "bos_token") and tokenizer.bos_token:
        bos = tokenizer.bos_token
        if isinstance(bos, int):
            llm_metadata.start_token.token_ids.ids.append(bos)
        else:
            llm_metadata.start_token.token_str = str(bos)

    stop_tokens: set[int] = set()
    if gen_config and hasattr(gen_config, "eos_token_id"):
        eos = gen_config.eos_token_id
        if isinstance(eos, int):
            stop_tokens.add(eos)
        elif isinstance(eos, list):
            stop_tokens.update(eos)
    elif hasattr(tokenizer, "eos_token_id") and tokenizer.eos_token_id is not None:
        stop_tokens.add(tokenizer.eos_token_id)
    for tok in stop_tokens:
        tu = llm_metadata.stop_tokens.add()
        tu.token_ids.ids.append(tok)

    if gen_config and getattr(gen_config, "do_sample", False):
        from ai_edge_litert.internal import sampler_params_pb2
        sp = llm_metadata.sampler_params
        if getattr(gen_config, "top_k", None):
            sp.type = sampler_params_pb2.SamplerParameters.TOP_K
            sp.k = gen_config.top_k
        if getattr(gen_config, "top_p", None):
            sp.type = sampler_params_pb2.SamplerParameters.TOP_P
            sp.p = gen_config.top_p
        if getattr(gen_config, "temperature", None):
            sp.temperature = gen_config.temperature

    if chat_template_path and chat_template_path.exists():
        llm_metadata.jinja_prompt_template = chat_template_path.read_text()
    elif hasattr(tokenizer, "chat_template") and tokenizer.chat_template:
        llm_metadata.jinja_prompt_template = tokenizer.chat_template

    llm_metadata.max_num_tokens = cache_length
    llm_metadata.llm_model_type.CopyFrom(
        llm_model_type_pb2.LlmModelType(gemma3=llm_model_type_pb2.Gemma3())
    )

    return llm_metadata


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--export-work", required=True,
                    help="Path to export_work/ dir (auxiliary.tflite, tokenizer.json)")
    ap.add_argument("--compiled-dir", required=True,
                    help="Path to compiled_mt6985/ dir (prefill_decode.tflite, embedder.tflite)")
    ap.add_argument("--output", required=True,
                    help="Output .litertlm path")
    ap.add_argument("--model", default="google/gemma-3-270m-it",
                    help="HF model ID for tokenizer / metadata (uses local cache)")
    ap.add_argument("--cache-length", type=int, default=128)
    args = ap.parse_args()

    export_work = pathlib.Path(args.export_work).resolve()
    compiled_dir = pathlib.Path(args.compiled_dir).resolve()
    output_path = pathlib.Path(args.output).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    main_compiled = compiled_dir / "prefill_decode.tflite"
    if not main_compiled.exists():
        print(f"ERROR: main compiled model not found: {main_compiled}", file=sys.stderr)
        return 1
    print(f"[bundle] main: {main_compiled} ({main_compiled.stat().st_size // 1024 // 1024} MB)")

    compiled_embedder = compiled_dir / "embedder.tflite"
    if not compiled_embedder.exists():
        print(f"WARNING: compiled embedder not found: {compiled_embedder} — skipping", file=sys.stderr)
        compiled_embedder = None
    else:
        print(f"[bundle] embedder: {compiled_embedder} ({compiled_embedder.stat().st_size // 1024 // 1024} MB)")

    auxiliary = export_work / "auxiliary.tflite"
    if not auxiliary.exists():
        print(f"WARNING: auxiliary.tflite not found at {auxiliary} — skipping", file=sys.stderr)
        auxiliary = None
    else:
        print(f"[bundle] auxiliary: {auxiliary} ({auxiliary.stat().st_size} bytes)")

    tokenizer_file = export_work / "tokenizer.json"
    if not tokenizer_file.exists():
        print(f"ERROR: tokenizer not found: {tokenizer_file}", file=sys.stderr)
        return 1

    chat_template_file = export_work / "chat_template.jinja"

    print(f"[bundle] Building llm_metadata (model={args.model}, cache_length={args.cache_length})...")
    llm_metadata = _build_llm_metadata_proto(
        args.model,
        args.cache_length,
        chat_template_file if chat_template_file.exists() else None,
    )
    llm_metadata_path = output_path.parent / "llm_metadata.pb"
    llm_metadata_path.write_bytes(llm_metadata.SerializeToString())
    print(f"[bundle] llm_metadata.pb: {llm_metadata_path.stat().st_size} bytes")

    print("[bundle] Assembling .litertlm...")
    from ai_edge_litert.internal import litertlm_builder

    builder = litertlm_builder.LitertLmFileBuilder()
    builder.add_system_metadata(
        litertlm_builder.Metadata(
            key="Authors",
            value="ODML",
            dtype=litertlm_builder.DType.STRING,
        )
    )
    builder.add_llm_metadata(str(llm_metadata_path))
    builder.add_hf_tokenizer(str(tokenizer_file))

    builder.add_tflite_model(
        str(main_compiled),
        litertlm_builder.TfLiteModelType.PREFILL_DECODE,
        backend_constraint="npu",
    )
    if compiled_embedder is not None:
        builder.add_tflite_model(
            str(compiled_embedder),
            litertlm_builder.TfLiteModelType.EMBEDDER,
            backend_constraint="npu",
        )
    if auxiliary is not None:
        builder.add_tflite_model(
            str(auxiliary),
            litertlm_builder.TfLiteModelType.AUX,
        )

    with open(output_path, "wb") as f:
        builder.build(f)

    sz = output_path.stat().st_size
    print(f"[bundle] Done: {output_path} ({sz // 1024 // 1024} MB, {sz:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
