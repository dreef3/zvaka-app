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
import dataclasses
import gc
import inspect
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


def _get_compile_quantization_recipe(label: str, model_family: str) -> str | None:
    if model_family != "gemma4":
        return "dynamic_wi4_afp32"

    recipe_map = {
        "prefill_decode": "weight_only_wi4_afp32",
        "embedder": "weight_only_wi4_afp32",
        "per_layer_embedder": "weight_only_wi8_afp32",
        "aux": None,
    }
    return recipe_map.get(label)


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

    override = os.environ.get("GEMMA4_EXPORT_DTYPE", "float32").lower()
    dtype_map = {
        "float32": torch_module.float32,
        "fp32": torch_module.float32,
        "bfloat16": torch_module.bfloat16,
        "bf16": torch_module.bfloat16,
        "float16": torch_module.float16,
        "fp16": torch_module.float16,
    }
    if override in dtype_map:
        return dtype_map[override]

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


def _patch_external_rope_embedder(external_rope_module) -> None:
    original_cls = external_rope_module.RoPEEmbedder

    class PatchedRoPEEmbedder(original_cls):
        def __init__(self, model):
            import torch

            torch.nn.Module.__init__(self)
            self.model = model

            model_root = self.model.model
            rope_owner = getattr(model_root, "language_model", model_root)

            self.rotary_emb = getattr(rope_owner, "original_rotary_emb", None)
            if self.rotary_emb is None:
                self.rotary_emb = getattr(rope_owner, "rotary_emb", None)
            if self.rotary_emb is None:
                raise AttributeError("Model does not expose a rotary embedding module")

            self.rotary_emb_local = getattr(
                rope_owner, "original_rotary_emb_local", None
            )
            if self.rotary_emb_local is None:
                self.rotary_emb_local = getattr(rope_owner, "rotary_emb_local", None)

            signature = inspect.signature(self.rotary_emb.forward)
            self._supports_layer_type = "layer_type" in signature.parameters

            layer_types = list(
                getattr(getattr(rope_owner, "config", None), "layer_types", [])
            )
            self._primary_layer_type = None
            self._secondary_layer_type = None
            if self._supports_layer_type and layer_types:
                if "full_attention" in layer_types:
                    self._primary_layer_type = "full_attention"
                else:
                    self._primary_layer_type = layer_types[0]

                if (
                    self.rotary_emb_local is None
                    and "sliding_attention" in layer_types
                    and self._primary_layer_type != "sliding_attention"
                ):
                    self._secondary_layer_type = "sliding_attention"

        def _call_rotary(self, rotary_emb, dummy, position_ids, layer_type=None):
            if self._supports_layer_type:
                return rotary_emb(dummy, position_ids, layer_type=layer_type)
            return rotary_emb(dummy, position_ids)

        def forward(self, input_pos):
            import torch

            dummy = torch.ones((1, 1, 1), dtype=torch.float32)
            position_ids = input_pos.unsqueeze(0)
            pos_emb = self._call_rotary(
                self.rotary_emb,
                dummy,
                position_ids,
                self._primary_layer_type,
            )
            ret = {
                "pos_emb_cos": pos_emb[0],
                "pos_emb_sin": pos_emb[1],
            }
            if self.rotary_emb_local is not None:
                pos_emb_local = self._call_rotary(
                    self.rotary_emb_local,
                    dummy,
                    position_ids,
                )
                ret.update(
                    {
                        "pos_emb_local_cos": pos_emb_local[0],
                        "pos_emb_local_sin": pos_emb_local[1],
                    }
                )
            elif self._secondary_layer_type is not None:
                pos_emb_local = self._call_rotary(
                    self.rotary_emb,
                    dummy,
                    position_ids,
                    self._secondary_layer_type,
                )
                ret.update(
                    {
                        "pos_emb_local_cos": pos_emb_local[0],
                        "pos_emb_local_sin": pos_emb_local[1],
                    }
                )
            return ret

    external_rope_module.RoPEEmbedder = PatchedRoPEEmbedder


def _patch_gemma4_split_cache(
    export_lib,
    cache_base_lib,
    split_cache_cache,
    split_cache_module,
    external_rope_preprocess_model,
) -> None:
    import copy
    import torch
    import torch.utils._pytree as pytree

    class Gemma4RotaryPositionEmbeddingInjector(torch.nn.Module):
        data = None

        def __init__(self, config):
            super().__init__()
            self.config = config
            self.data = {}

        def forward(self, x, position_ids, layer_type=None):
            del x, position_ids
            if layer_type is None:
                layer_type = "full_attention"
            data = self.data
            assert data is not None and layer_type in data
            return data[layer_type]

    def inject_rotary_position_embedding_for_gemma4(model):
        model_root = model.model
        rope_owner = getattr(model_root, "language_model", model_root)
        if hasattr(rope_owner, "original_rotary_emb"):
            return model
        rope_owner.original_rotary_emb = rope_owner.rotary_emb
        rope_owner.rotary_emb = Gemma4RotaryPositionEmbeddingInjector(rope_owner.config)
        return model

    external_rope_preprocess_model.inject_rotary_position_embedding = (
        inject_rotary_position_embedding_for_gemma4
    )

    class DeviceAwareCacheTuple(tuple):
        def to(self, device):
            return DeviceAwareCacheTuple(
                value.to(device) if value is not None else None for value in self
            )

    class LiteRTLMSplitCacheLayerForGemma4(split_cache_cache.LiteRTLMSplitCacheLayer):
        def update(self, key_states, value_states, *args, **kwargs):
            key_states, value_states = super().update(
                key_states, value_states, *args, **kwargs
            )
            key_states = DeviceAwareCacheTuple(key_states)
            value_states = DeviceAwareCacheTuple(value_states)
            self.keys = key_states
            self.values = value_states
            return key_states, value_states

        @classmethod
        def _infer_cache_shape_from_config(
            cls,
            model_config,
            layer_index,
            export_config,
            **kwargs,
        ):
            del kwargs
            cache_length = export_config.cache_length
            batch_size = export_config.batch_size
            k_ts_idx = export_config.k_ts_idx
            v_ts_idx = export_config.v_ts_idx
            num_kv_heads = model_config.num_key_value_heads
            layer_type = model_config.layer_types[layer_index]
            if layer_type == "full_attention":
                num_kv_heads = (
                    getattr(model_config, "num_global_key_value_heads", None)
                    or num_kv_heads
                )
            embed_size_per_head = (
                getattr(model_config, "head_dim", None)
                or model_config.hidden_size // model_config.num_attention_heads
            )
            if layer_type == "full_attention":
                embed_size_per_head = (
                    getattr(model_config, "global_head_dim", None)
                    or embed_size_per_head
                )

            if k_ts_idx == 2:
                k_cache_shape = (
                    1,
                    batch_size * num_kv_heads,
                    cache_length,
                    embed_size_per_head,
                )
            else:
                k_cache_shape = (
                    1,
                    batch_size * num_kv_heads,
                    embed_size_per_head,
                    cache_length,
                )
            if v_ts_idx == 2:
                v_cache_shape = (
                    1,
                    batch_size * num_kv_heads,
                    cache_length,
                    embed_size_per_head,
                )
            else:
                v_cache_shape = (
                    1,
                    batch_size * num_kv_heads,
                    embed_size_per_head,
                    cache_length,
                )
            return k_cache_shape, v_cache_shape

    @cache_base_lib.register_cache_implementation
    class LiteRTLMSplitCacheForGemma4(split_cache_cache.LiteRTLMSplitCache):
        @classmethod
        def create_from_config(cls, model_config, export_config, **kwargs):
            num_layers = model_config.num_hidden_layers
            num_shared_layers = model_config.num_kv_shared_layers
            num_unshared_layers = num_layers - num_shared_layers
            layers = []
            for layer_index in range(num_unshared_layers):
                layers.append(
                    LiteRTLMSplitCacheLayerForGemma4.create_from_config(
                        model_config,
                        layer_index,
                        export_config,
                        **kwargs,
                    )
                )
            return cls(layers)

        def insert_dummy_cache_layers(self, model_config):
            num_layers = model_config.num_hidden_layers
            num_shared_layers = model_config.num_kv_shared_layers
            num_unshared_layers = num_layers - num_shared_layers
            assert len(self.layers) == num_unshared_layers
            for i in range(num_shared_layers):
                self.layers.append(copy.copy(self.layers[i % num_unshared_layers]))
            return self

        def remove_dummy_cache_layers(self, model_config):
            num_layers = model_config.num_hidden_layers
            num_shared_layers = model_config.num_kv_shared_layers
            num_unshared_layers = num_layers - num_shared_layers
            assert len(self.layers) == num_layers
            self.layers = self.layers[:num_unshared_layers]
            return self

    def _flatten_gemma4_split_cache(kvc):
        flattened = []
        flat_names = []
        num_layers = len(kvc.layers)
        layer_0 = kvc.layers[0]
        batch_size = layer_0.get_batch_size()
        k_ts_idx = layer_0.get_k_ts_idx()
        v_ts_idx = layer_0.get_v_ts_idx()
        for i, cache_layer in enumerate(kvc.layers):
            flattened.append(cache_layer.keys[0])
            flat_names.append(f"k_{i}")
            flattened.append(cache_layer.values[0])
            flat_names.append(f"v_{i}")
            if cache_layer.keys[1] is not None:
                flattened.append(cache_layer.keys[1])
                flat_names.append(f"k_{i}_slice")
                flattened.append(cache_layer.values[1])
                flat_names.append(f"v_{i}_slice")
        return flattened, [flat_names, (batch_size, num_layers, k_ts_idx, v_ts_idx)]

    def _unflatten_gemma4_split_cache(values, context):
        flat_names = context[0]
        batch_size = context[1][0]
        num_layers = context[1][1]
        k_ts_idx = context[1][2]
        v_ts_idx = context[1][3]
        kv_entries = []
        for i in range(num_layers):
            k_cache = values[flat_names.index(f"k_{i}")]
            v_cache = values[flat_names.index(f"v_{i}")]
            k_slice = (
                values[flat_names.index(f"k_{i}_slice")]
                if f"k_{i}_slice" in flat_names
                else None
            )
            v_slice = (
                values[flat_names.index(f"v_{i}_slice")]
                if f"v_{i}_slice" in flat_names
                else None
            )
            kv_entries.append(
                LiteRTLMSplitCacheLayerForGemma4(
                    key_cache=(k_cache, k_slice),
                    value_cache=(v_cache, v_slice),
                    batch_size=batch_size,
                    k_ts_idx=k_ts_idx,
                    v_ts_idx=v_ts_idx,
                )
            )
        return LiteRTLMSplitCacheForGemma4(kv_entries)

    def _flatten_gemma4_split_cache_with_keys(kvc):
        flattened, (flat_names, _) = _flatten_gemma4_split_cache(kvc)
        return [
            (pytree.MappingKey(k), v) for k, v in zip(flat_names, flattened)
        ], flat_names

    pytree.register_pytree_node(
        LiteRTLMSplitCacheForGemma4,
        _flatten_gemma4_split_cache,
        _unflatten_gemma4_split_cache,
        flatten_with_keys_fn=_flatten_gemma4_split_cache_with_keys,
        serialized_type_name="",
    )

    class Gemma4SplitCacheBase(
        split_cache_module.LiteRTSplitCacheExportableModuleForDecoderOnlyLM
    ):
        def _rope_owner(self):
            return self.model.model.language_model

        def _mask_inputs(self, mask):
            mask_global = mask["global"]
            mask_local = mask.get("local")
            if mask_local is not None:
                return {
                    "full_attention": mask_global,
                    "sliding_attention": mask_local,
                }
            return mask_global

        def _set_rope_data(self, pos_emb):
            rope_owner = self._rope_owner()
            rope_owner.rotary_emb.data = {
                "full_attention": (
                    pos_emb["cos"].permute(0, 2, 1, 3).squeeze(0),
                    pos_emb["sin"].permute(0, 2, 1, 3).squeeze(0),
                )
            }
            if "local_cos" in pos_emb and "local_sin" in pos_emb:
                rope_owner.rotary_emb.data["sliding_attention"] = (
                    pos_emb["local_cos"].permute(0, 2, 1, 3).squeeze(0),
                    pos_emb["local_sin"].permute(0, 2, 1, 3).squeeze(0),
                )

        def _static_positions(self, embeddings):
            return torch.arange(
                embeddings.shape[1],
                dtype=torch.int32,
                device=embeddings.device,
            )

        def _get_input(self, batch_size, input_length, cache_length):
            model_config = self.model.config.text_config
            full_head_dim = getattr(model_config, "global_head_dim", None) or getattr(
                model_config, "head_dim", None
            )
            local_head_dim = getattr(model_config, "head_dim", None) or full_head_dim
            sample_inputs = {
                "embeddings": torch.ones(
                    (batch_size, input_length, model_config.hidden_size),
                    dtype=torch.float32,
                ),
                "per_layer_embeddings": torch.ones(
                    (
                        batch_size,
                        input_length,
                        model_config.num_hidden_layers,
                        model_config.hidden_size_per_layer_input,
                    ),
                    dtype=torch.float32,
                ),
            }
            pos_emb = {
                "cos": torch.ones(
                    (1, input_length, 1, full_head_dim), dtype=torch.float32
                ),
                "sin": torch.ones(
                    (1, input_length, 1, full_head_dim), dtype=torch.float32
                ),
            }
            if getattr(model_config, "sliding_window", None):
                pos_emb.update(
                    {
                        "local_cos": torch.ones(
                            (1, input_length, 1, local_head_dim), dtype=torch.float32
                        ),
                        "local_sin": torch.ones(
                            (1, input_length, 1, local_head_dim), dtype=torch.float32
                        ),
                    }
                )
            mask_shape = (1, 1, input_length, cache_length + input_length)
            mask = {"global": torch.ones(mask_shape, dtype=torch.float32)}
            if getattr(model_config, "sliding_window", None):
                mask["local"] = torch.ones(mask_shape, dtype=torch.float32)
            sample_inputs.update({"mask": mask, "pos_emb": pos_emb})
            return sample_inputs

        def adapt_inputs(
            self, embeddings, per_layer_embeddings, pos_emb, mask, kv_cache
        ):
            positions = self._static_positions(embeddings)
            kv_cache = kv_cache.insert_dummy_cache_layers(self.model.config.text_config)
            kv_cache.set_cache_runtime_args({"cache_position": positions})
            self._set_rope_data(pos_emb)
            return {
                "inputs_embeds": embeddings,
                "per_layer_inputs": per_layer_embeddings,
                "position_ids": positions.unsqueeze(0),
                "past_key_values": kv_cache,
                "cache_position": positions,
                "attention_mask": self._mask_inputs(mask),
                "use_cache": True,
            }

        def post_process_kv_cache(self, output_cache):
            output_cache = output_cache.remove_dummy_cache_layers(
                self.model.config.text_config
            )
            return super().post_process_kv_cache(output_cache)

    class Gemma4SplitCachePrefillExportableModule(Gemma4SplitCacheBase):
        def forward(self, embeddings, per_layer_embeddings, pos_emb, mask, kv_cache):
            inputs = self.adapt_inputs(
                embeddings, per_layer_embeddings, pos_emb, mask, kv_cache
            )
            inputs |= self.attention_kwargs()
            output = self.model.model.language_model(**inputs)
            return self.post_process_kv_cache(output.past_key_values)

        def get_sample_inputs(self, model_config, **kwargs):
            del kwargs
            export_config = self.export_config
            kv_cache_inputs, _ = self.get_sample_kv_cache(model_config)
            sample_inputs = {}
            for prefill_length in export_config.prefill_lengths:
                sample_inputs[f"prefill_{prefill_length}"] = (
                    {
                        **kv_cache_inputs,
                        **self._get_input(
                            export_config.batch_size,
                            prefill_length,
                            export_config.cache_length,
                        ),
                    },
                    {},
                )
            return sample_inputs

    class Gemma4SplitCacheGenerateExportableModule(Gemma4SplitCacheBase):
        def forward(self, embeddings, per_layer_embeddings, pos_emb, mask, kv_cache):
            inputs = self.adapt_inputs(
                embeddings, per_layer_embeddings, pos_emb, mask, kv_cache
            )
            inputs |= self.attention_kwargs()
            output = self.model.model.language_model(**inputs)
            ret = self.post_process_kv_cache(output.past_key_values)
            ret["logits"] = self.model.lm_head(output.last_hidden_state)
            return ret

        def get_sample_inputs(self, model_config, **kwargs):
            del kwargs
            export_config = self.export_config
            kv_cache_inputs, _ = self.get_sample_kv_cache(model_config)
            return {
                "decode": (
                    {
                        **kv_cache_inputs,
                        **self._get_input(
                            export_config.batch_size,
                            1,
                            export_config.cache_length,
                        ),
                    },
                    {},
                )
            }

    original_get_prefill_decode_exportable_cls = (
        export_lib.get_prefill_decode_exportable_cls
    )

    def patched_get_prefill_decode_exportable_cls(model_config, export_config):
        if model_config.model_type == "gemma4" and export_config.split_cache:
            return (
                Gemma4SplitCachePrefillExportableModule,
                Gemma4SplitCacheGenerateExportableModule,
            )
        return original_get_prefill_decode_exportable_cls(model_config, export_config)

    export_lib.get_prefill_decode_exportable_cls = (
        patched_get_prefill_decode_exportable_cls
    )


def _maybe_disable_gemma4_rms_hlfb(normalization_module) -> None:
    if os.environ.get("GEMMA4_DISABLE_RMS_HLFB", "1") != "1":
        return

    def plain_rms_norm(
        x,
        w,
        eps,
        final_scale,
    ):
        normalized = x.float()
        normalized = normalized * normalization_module.torch.rsqrt(
            normalized.pow(2).mean(-1, keepdim=True) + eps
        )
        normalized = normalized.type_as(x) * final_scale
        return normalized * w

    normalization_module.rms_norm_with_hlfb = plain_rms_norm


def _maybe_override_mediatek_sdk_libs() -> None:
    override_dir = os.environ.get("MEDIATEK_SDK_LIB_DIR")
    override_subdir = os.environ.get("MEDIATEK_SDK_LIB_SUBDIR")
    if not override_dir and not override_subdir:
        return

    import ai_edge_litert_sdk_mediatek

    sdk_root = ai_edge_litert_sdk_mediatek.get_sdk_path()
    if override_dir:
        resolved = pathlib.Path(override_dir).expanduser().resolve()
    else:
        if sdk_root is None:
            raise RuntimeError("MediaTek SDK root is unavailable")
        resolved = (sdk_root / override_subdir).resolve()

    if not resolved.exists():
        raise FileNotFoundError(
            f"MediaTek SDK library override path does not exist: {resolved}"
        )

    def path_to_sdk_libs(version: str = "v8") -> pathlib.Path:
        del version
        return resolved

    ai_edge_litert_sdk_mediatek.path_to_sdk_libs = path_to_sdk_libs
    print(f"Using MediaTek SDK libs override: {resolved}", file=sys.stderr)


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

    requested_dtype = _resolve_model_load_dtype(torch, None, model_family)

    config = transformers.AutoConfig.from_pretrained(
        model,
        dtype=requested_dtype,
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
    from litert_torch.generative.export_hf.core import cache_base as cache_base_lib
    from litert_torch.generative.export_hf.core.external_rope import (
        exportable_module as external_rope_module,
    )
    from litert_torch.generative.export_hf.core.external_rope import (
        preprocess_model as external_rope_preprocess_model,
    )
    from litert_torch.generative.layers import normalization
    from litert_torch.generative.export_hf.core.split_cache import (
        cache as split_cache_cache,
    )
    from litert_torch.generative.export_hf.core.split_cache import (
        exportable_module as split_cache_module,
    )

    from ai_edge_litert.aot import aot_compile as aot_compile_lib
    from ai_edge_litert.aot.aot_compile import aot_types
    from ai_edge_litert.aot.vendors.mediatek import target as mediatek_target
    from ai_edge_quantizer import quantizer as quantizer_lib
    from ai_edge_quantizer import recipe as recipe_lib
    from ai_edge_litert.internal import litertlm_builder

    _maybe_override_mediatek_sdk_libs()

    prefill_lengths = _parse_prefill_lengths(args.prefill_lengths)

    _patch_external_rope_embedder(external_rope_module)
    _patch_gemma4_split_cache(
        export_lib,
        cache_base_lib,
        split_cache_cache,
        split_cache_module,
        external_rope_preprocess_model,
    )
    if args.model_family == "gemma4":
        _maybe_disable_gemma4_rms_hlfb(normalization)

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
        experimental_lightweight_conversion=(
            args.model_family == "gemma4"
            and os.environ.get("GEMMA4_LIGHTWEIGHT_CONVERSION", "0") != "0"
        ),
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
    if args.model_family == "gemma4":
        export_config = dataclasses.replace(
            export_config,
            split_cache=True,
            externalize_rope=True,
            cache_implementation="LiteRTLMSplitCacheForGemma4",
            quantization_recipe=None,
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

        compile_input_path = pathlib.Path(input_path)
        quantization_recipe = _get_compile_quantization_recipe(label, args.model_family)
        if quantization_recipe:
            quantized_input_path = (
                compiled_dir / f"{label}_{quantization_recipe}.tflite"
            )
            quantizer = quantizer_lib.Quantizer(str(compile_input_path))
            quantizer.load_quantization_recipe(
                getattr(recipe_lib, quantization_recipe)()
            )
            quantizer.quantize().export_model(str(quantized_input_path), overwrite=True)
            compile_input_path = quantized_input_path

        result = aot_compile_lib.aot_compile(
            aot_types.Model.create_from_path(compile_input_path),
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
        compiled_auxiliary = pathlib.Path(exported.auxiliary_model_path)
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
