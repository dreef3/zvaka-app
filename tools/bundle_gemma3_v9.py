#!/usr/bin/env python3
"""
bundle_gemma3_v9.py — Bundle pre-compiled Gemma 3 270M artifacts into a
                       MT6985-targeted .litertlm without re-running HF export.

Usage:
  python3 bundle_gemma3_v9.py \
    --work-dir  ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9 \
    --output    ~/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9/gemma3-270m-it_mt6985_v9.litertlm \
    [--model    google/gemma-3-270m-it] \
    [--cache-length 128]

Expected directory layout under --work-dir:
  export_work/
    model_quantized.ns_patched.tflite   # patcher output, main LLM
    embedder_quantized.ns_patched.tflite # patcher output, embedder
    auxiliary.tflite                    # pass-through (no NPU compile)
    tokenizer.json                      # LiteRT-LM HF tokenizer
    tokenizer_config.json
    chat_template.jinja                 # optional
  compiled_v9/
    compiled_MediaTek_MT6985.tflite     # aot_compile_gemma3_v9.py output for main LLM
"""
from __future__ import annotations

import argparse
import os
import pathlib
import sys
import tempfile

os.environ.setdefault("JAX_PLATFORMS", "cpu")
os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")


def _add_python_path(path: pathlib.Path) -> None:
    s = str(path.resolve())
    if s not in sys.path:
        sys.path.insert(0, s)


def _load_tokenizer_and_gen_config(model_id: str):
    from transformers import AutoTokenizer, GenerationConfig
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    try:
        gen_config = GenerationConfig.from_pretrained(model_id)
    except Exception:
        gen_config = None
    return tokenizer, gen_config


def _build_llm_metadata_proto(tokenizer, gen_config, cache_length: int, chat_template_path: pathlib.Path | None):
    from ai_edge_litert.internal import llm_metadata_pb2, llm_model_type_pb2

    llm_metadata = llm_metadata_pb2.LlmMetadata()

    # BOS token
    if hasattr(tokenizer, "bos_token") and tokenizer.bos_token:
        bos = tokenizer.bos_token
        if isinstance(bos, int):
            llm_metadata.start_token.token_ids.ids.append(bos)
        else:
            llm_metadata.start_token.token_str = str(bos)

    # Stop / EOS tokens
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

    # Sampler params
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

    # Chat template — prefer jinja file if present
    if chat_template_path and chat_template_path.exists():
        jinja_tmpl = chat_template_path.read_text()
        llm_metadata.jinja_prompt_template = jinja_tmpl
    elif hasattr(tokenizer, "chat_template") and tokenizer.chat_template:
        llm_metadata.jinja_prompt_template = tokenizer.chat_template

    llm_metadata.max_num_tokens = cache_length

    # Gemma3 model type
    llm_metadata.llm_model_type.CopyFrom(
        llm_model_type_pb2.LlmModelType(gemma3=llm_model_type_pb2.Gemma3())
    )

    return llm_metadata


def _restore_fc_bias_compiled(compiled_path: pathlib.Path, work_dir: pathlib.Path) -> pathlib.Path:
    """Undo Pass 2 of add_reshape_new_shape.py in compiled_MediaTek_MT6985.tflite.

    Pass 2 sets FC ops' absent bias (inputs[2]=-1) to inputs[0] so NeuronAdapter's
    DLA float32 type check passes at compile time.  On device the TFLite CPU kernel
    validates NumElements(bias)==SizeOfDimension(filter,0) and fails.  Restore -1.
    """
    import struct

    def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
    def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
    def _u16(b, o): return struct.unpack_from("<H", b, o)[0]
    def _s8(b, o):  return struct.unpack_from("<b", b, o)[0]

    def _vf(b, tp, fid):
        vt  = tp - _r32(b, tp)
        vsz = _u16(b, vt)
        nf  = (vsz - 4) // 2
        if fid >= nf: return None
        off = _u16(b, vt + 4 + fid * 2)
        return None if off == 0 else tp + off

    def _vec_tables(b, fpos):
        vp = fpos + _r32(b, fpos)
        n  = _u32(b, vp)
        return [vp + 4 + i * 4 + _r32(b, vp + 4 + i * 4) for i in range(n)]

    def _bc(b, oc_pos):
        f3 = _vf(b, oc_pos, 3)
        if f3: return _r32(b, f3)
        f0 = _vf(b, oc_pos, 0)
        if f0: return _s8(b, f0)
        return 0

    FC_BC = 9
    buf = bytearray(compiled_path.read_bytes())
    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)
    if oc_fpos is None or sg_fpos is None:
        print("[bundle] restore_fc_bias: no op-codes or subgraphs found, skipping", file=sys.stderr)
        return compiled_path

    oc_tables = _vec_tables(buf, oc_fpos)
    sg_tables = _vec_tables(buf, sg_fpos)
    count = 0

    for sg_pos in sg_tables:
        op_fpos = _vf(buf, sg_pos, 3)
        if op_fpos is None:
            continue
        for op_pos in _vec_tables(buf, op_fpos):
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == FC_BC):
                continue
            inp_fpos = _vf(buf, op_pos, 1)
            if inp_fpos is None:
                continue
            ivp = inp_fpos + _r32(buf, inp_fpos)
            if _u32(buf, ivp) < 3:
                continue
            bias_slot = ivp + 4 + 8   # inputs[2]
            data_slot = ivp + 4       # inputs[0]
            if _r32(buf, bias_slot) == _r32(buf, data_slot):
                struct.pack_into("<i", buf, bias_slot, -1)
                count += 1

    print(f"[bundle] FC bias slots restored: {count}", file=sys.stderr)
    if count == 0:
        return compiled_path

    out_path = work_dir / "compiled_v9_fc_restored" / compiled_path.name
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(buf)
    print(f"[bundle] Restored model written: {out_path}", file=sys.stderr)
    return out_path


def _fix_pack_scalar_inputs(compiled_path: pathlib.Path, work_dir: pathlib.Path) -> pathlib.Path:
    """Restore PACK inputs to scalar (shape=[]) so PACK(N×[]) → [N] (1D).

    Two categories of PACK inputs need fixing after Pass 0 patched shape=[] → [1]:

      1. Constant scalar tensors (no producer op): zero declared shape count → [].
      2. RESHAPE-produced scalars: zero the RESHAPE's shape-spec tensor embedded
         buffer count → RESHAPE produces scalar at runtime; also zero both the
         shape-spec tensor's and the PACK input tensor's declared shapes.

    This fixes "PACK failed to prepare" (mixed [] / [1] inputs) and the
    downstream "NumDimensions(start_indices) == 1 was not true" in DUS.
    """
    import struct

    def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
    def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
    def _u16(b, o): return struct.unpack_from("<H", b, o)[0]
    def _s8(b, o):  return struct.unpack_from("<b", b, o)[0]

    def _vf(b, tp, fid):
        vt  = tp - _r32(b, tp)
        vsz = _u16(b, vt)
        nf  = (vsz - 4) // 2
        if fid >= nf: return None
        off = _u16(b, vt + 4 + fid * 2)
        return None if off == 0 else tp + off

    def _vec_tables(b, fpos):
        vp = fpos + _r32(b, fpos); n = _u32(b, vp)
        return [vp + 4 + i * 4 + _r32(b, vp + 4 + i * 4) for i in range(n)]

    def _vec_i32(b, fpos):
        vp = fpos + _r32(b, fpos); n = _u32(b, vp)
        return [_r32(b, vp + 4 + i * 4) for i in range(n)]

    def _bc(b, oc_pos):
        f3 = _vf(b, oc_pos, 3)
        if f3: return _r32(b, f3)
        f0 = _vf(b, oc_pos, 0)
        if f0: return _s8(b, f0)
        return 0

    PACK_BC    = 83
    RESHAPE_BC = 22

    buf = bytearray(compiled_path.read_bytes())
    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)
    buf_fpos  = _vf(buf, model_pos, 4)
    if oc_fpos is None or sg_fpos is None:
        return compiled_path

    oc_tables     = _vec_tables(buf, oc_fpos)
    sg_tables     = _vec_tables(buf, sg_fpos)
    buffer_tables = _vec_tables(buf, buf_fpos) if buf_fpos else []
    count = 0

    for sg_pos in sg_tables:
        t_fpos  = _vf(buf, sg_pos, 0)
        op_fpos = _vf(buf, sg_pos, 3)
        if t_fpos is None or op_fpos is None:
            continue
        tensor_tables = _vec_tables(buf, t_fpos)
        op_tables     = _vec_tables(buf, op_fpos)

        # Build producer map: tensor_idx → op_pos
        producer: dict[int, int] = {}
        for op_pos in op_tables:
            out_fpos = _vf(buf, op_pos, 2)
            if out_fpos is None:
                continue
            for tidx in _vec_i32(buf, out_fpos):
                if 0 <= tidx < len(tensor_tables):
                    producer[tidx] = op_pos

        zeroed_shape_vecs: set[int] = set()
        zeroed_buf_data: set[int]   = set()

        def _zero_shape_vec(tt: int) -> bool:
            ts_fpos = _vf(buf, tt, 0)
            if ts_fpos is None:
                return False
            shape_vp = ts_fpos + _r32(buf, ts_fpos)
            if shape_vp in zeroed_shape_vecs:
                return False
            if _u32(buf, shape_vp) == 0:
                zeroed_shape_vecs.add(shape_vp)
                return False
            struct.pack_into("<I", buf, shape_vp, 0)
            zeroed_shape_vecs.add(shape_vp)
            return True

        def _zero_embedded_buffer_count(buf_id: int) -> bool:
            if buf_id == 0 or buf_id >= len(buffer_tables):
                return False
            bt = buffer_tables[buf_id]
            f0 = _vf(buf, bt, 0)
            if f0 is None:
                return False
            data_vp = f0 + _r32(buf, f0)
            if data_vp in zeroed_buf_data:
                return False
            if _u32(buf, data_vp) == 0:
                zeroed_buf_data.add(data_vp)
                return False
            struct.pack_into("<I", buf, data_vp, 0)
            zeroed_buf_data.add(data_vp)
            return True

        for op_pos in op_tables:
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == PACK_BC):
                continue
            inp_fpos = _vf(buf, op_pos, 1)
            if inp_fpos is None:
                continue

            for tidx in _vec_i32(buf, inp_fpos):
                if not (0 <= tidx < len(tensor_tables)):
                    continue
                tt = tensor_tables[tidx]
                ts_fpos = _vf(buf, tt, 0)
                if ts_fpos is None:
                    continue
                shape_vp = ts_fpos + _r32(buf, ts_fpos)
                if _u32(buf, shape_vp) != 1:
                    continue
                if _r32(buf, shape_vp + 4) != 1:
                    continue

                prod_op = producer.get(tidx)
                if prod_op is not None:
                    # RESHAPE-produced PACK input: zero the shape-spec buffer
                    prod_oc_idx_fpos = _vf(buf, prod_op, 0)
                    prod_oc_idx = (0 if prod_oc_idx_fpos is None
                                   else _u32(buf, prod_oc_idx_fpos))
                    if not (prod_oc_idx < len(oc_tables)
                            and _bc(buf, oc_tables[prod_oc_idx]) == RESHAPE_BC):
                        continue
                    prod_inp_fpos = _vf(buf, prod_op, 1)
                    if prod_inp_fpos is None:
                        continue
                    prod_inputs = _vec_i32(buf, prod_inp_fpos)
                    if len(prod_inputs) < 2 or prod_inputs[1] < 0:
                        continue
                    spec_tidx = prod_inputs[1]
                    if spec_tidx >= len(tensor_tables):
                        continue
                    spec_tt = tensor_tables[spec_tidx]
                    spec_bid_fpos = _vf(buf, spec_tt, 2)
                    if spec_bid_fpos is None:
                        continue
                    spec_buf_id = _u32(buf, spec_bid_fpos)
                    if _zero_embedded_buffer_count(spec_buf_id):
                        print(f"[bundle]   zeroed shape-spec buffer {spec_buf_id} "
                              f"for RESHAPE→t{tidx} (PACK input)", file=sys.stderr)
                        count += 1
                    _zero_shape_vec(spec_tt)
                    _zero_shape_vec(tt)
                else:
                    # Constant PACK input: zero its declared shape
                    if _zero_shape_vec(tt):
                        print(f"[bundle]   zeroed declared shape of constant t{tidx} "
                              f"(PACK scalar input)", file=sys.stderr)
                        count += 1

    print(f"[bundle] PACK scalar inputs fixed: {count}", file=sys.stderr)
    if count == 0:
        return compiled_path

    out_path = work_dir / "compiled_v9_pack_fixed" / compiled_path.name
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(buf)
    print(f"[bundle] PACK-fixed model written: {out_path}", file=sys.stderr)
    return out_path


def _aot_compile_embedder(embedder_input: pathlib.Path, output_dir: pathlib.Path, sdk_subdir: str, litert_torch_root: pathlib.Path) -> pathlib.Path:
    _add_python_path(litert_torch_root)

    import ai_edge_litert_sdk_mediatek
    from ai_edge_litert.aot import aot_compile as aot_compile_lib
    from ai_edge_litert.aot.aot_compile import aot_types
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target

    sdk_root = ai_edge_litert_sdk_mediatek.get_sdk_path()
    resolved = (sdk_root / sdk_subdir).resolve()
    if not resolved.exists():
        raise FileNotFoundError(f"SDK lib path not found: {resolved}")

    def path_to_sdk_libs(version="v8"):
        return resolved
    ai_edge_litert_sdk_mediatek.path_to_sdk_libs = path_to_sdk_libs

    dla_dir = tempfile.mkdtemp(prefix="mtknn_dla_emb_")
    os.environ["MTKNN_ADAPTER_DLA_DIR"] = dla_dir

    output_dir.mkdir(parents=True, exist_ok=True)
    target = mediatek_target.Target(mediatek_target.SocModel.MT6985)

    print(f"[bundle] AOT-compiling embedder: {embedder_input}", file=sys.stderr)
    result = aot_compile_lib.aot_compile(
        aot_types.Model.create_from_path(embedder_input),
        output_dir=str(output_dir),
        target=target,
    )

    if result.failed_backends:
        for backend, err in result.failed_backends:
            print(f"[bundle] FAILED {backend.target_id}: {err}", file=sys.stderr)
        raise RuntimeError("Embedder AOT compilation failed")

    if not result.models_with_backend:
        raise RuntimeError("Embedder AOT: no compiled models produced")

    backend, model = result.models_with_backend[0]
    out_path = output_dir / f"compiled_{backend.target_id}.tflite"
    model.save(out_path, export_only=True)
    sz = out_path.stat().st_size
    print(f"[bundle] Embedder compiled: {out_path} ({sz:,} bytes)", file=sys.stderr)
    return out_path


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work-dir", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--model", default="google/gemma-3-270m-it")
    ap.add_argument("--cache-length", type=int, default=128)
    ap.add_argument("--sdk-subdir", default="v9_0_3/host/lib")
    ap.add_argument("--litert-torch-root", default=os.path.expanduser("~/src/litert-torch"))
    ap.add_argument("--skip-embedder-compile", action="store_true",
                    help="Use existing compiled embedder in compiled_v9_emb/")
    args = ap.parse_args()

    work_dir = pathlib.Path(args.work_dir).resolve()
    export_work = work_dir / "export_work"
    compiled_v9 = work_dir / "compiled_v9"
    output_path = pathlib.Path(args.output).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Locate main compiled model
    main_compiled = compiled_v9 / "compiled_MediaTek_MT6985.tflite"
    if not main_compiled.exists():
        print(f"ERROR: main compiled model not found: {main_compiled}", file=sys.stderr)
        return 1

    # Undo Pass 2 FC bias hack before bundling (restore inputs[2] = -1).
    # Pass 2 pointed inputs[2] at the data tensor for NeuronAdapter's DLA
    # float32 type check; the TFLite CPU kernel validates bias shape and fails.
    main_compiled = _restore_fc_bias_compiled(main_compiled, work_dir)

    # Undo Pass-0 shape patch for PACK-only scalar tensors.
    # Pass 0 changed scalar tensors (shape=[]) to shape=[1] for MUL legalization.
    # PACK ops that build DYNAMIC_UPDATE_SLICE start_indices from scalars now get
    # shape=[1] inputs → PACK output is [N,1] (2D) instead of [N] (1D) →
    # DYNAMIC_UPDATE_SLICE NumDimensions check fails.  Restore those to shape=[].
    main_compiled = _fix_pack_scalar_inputs(main_compiled, work_dir)

    # Locate / compile embedder
    compiled_v9_emb = work_dir / "compiled_v9_emb"
    compiled_embedder = compiled_v9_emb / "compiled_MediaTek_MT6985.tflite"

    if not args.skip_embedder_compile or not compiled_embedder.exists():
        embedder_patched = export_work / "embedder_quantized.ns_patched.tflite"
        if not embedder_patched.exists():
            print(f"ERROR: patched embedder not found: {embedder_patched}", file=sys.stderr)
            return 1
        compiled_embedder = _aot_compile_embedder(
            embedder_patched,
            compiled_v9_emb,
            args.sdk_subdir,
            pathlib.Path(args.litert_torch_root),
        )

    # Auxiliary model (no AOT)
    auxiliary = export_work / "auxiliary.tflite"

    # Tokenizer file (LiteRT-LM HF tokenizer format)
    tokenizer_file = export_work / "tokenizer.json"
    if not tokenizer_file.exists():
        print(f"ERROR: tokenizer not found: {tokenizer_file}", file=sys.stderr)
        return 1

    # Chat template
    chat_template_file = export_work / "chat_template.jinja"

    # Load HF tokenizer + gen config (from cache, no model weights)
    print(f"[bundle] Loading tokenizer from {args.model} (cache only)...", file=sys.stderr)
    tokenizer, gen_config = _load_tokenizer_and_gen_config(args.model)

    # Build llm_metadata.pb
    print("[bundle] Building llm_metadata.pb...", file=sys.stderr)
    llm_metadata = _build_llm_metadata_proto(
        tokenizer, gen_config, args.cache_length,
        chat_template_file if chat_template_file.exists() else None
    )
    llm_metadata_path = work_dir / "llm_metadata.pb"
    llm_metadata_path.write_bytes(llm_metadata.SerializeToString())
    print(f"[bundle] llm_metadata.pb written ({llm_metadata_path.stat().st_size} bytes)", file=sys.stderr)

    # Bundle .litertlm
    print("[bundle] Assembling .litertlm...", file=sys.stderr)
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
    if compiled_embedder.exists():
        builder.add_tflite_model(
            str(compiled_embedder),
            litertlm_builder.TfLiteModelType.EMBEDDER,
            backend_constraint="npu",
        )
    if auxiliary.exists():
        builder.add_tflite_model(
            str(auxiliary),
            litertlm_builder.TfLiteModelType.AUX,
        )

    with open(output_path, "wb") as f:
        builder.build(f)

    sz = output_path.stat().st_size
    print(f"[bundle] Done: {output_path} ({sz:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
