#!/usr/bin/env python3
"""Insert an ADD-with-zero op after the embedder's final output tensor.

The Gemma 3 270M embedder TFLite (weight_only_wi4_afp32) produces its output
via a chain ending in MUL(embedding, scale). XNNPack or the CPU reference
kernel may leave the output tensor pointing to an internal strided buffer.
When the MediaTek DLA dispatch API tries to register this tensor as a DLA
input, it fails with "Tensor strides are not supported".

Fix: append ADD(original_output, scalar_zero) -> new_output_tensor.
ADD always materialises a fresh, contiguous output buffer in XNNPack.
The scalar zero broadcasts to any runtime shape, so this works for both
decode (seq=1) and prefill (seq=128) invocations.

Usage:
    python densify_embedder_output.py INPUT.tflite OUTPUT.tflite
"""

import pathlib
import struct
import sys

import flatbuffers
from ai_edge_litert import schema_py_generated as schema


def densify(input_path: pathlib.Path, output_path: pathlib.Path) -> None:
    data = input_path.read_bytes()
    buf = bytearray(data)
    model = schema.ModelT.InitFromPackedBuf(buf, 0)

    print(f"Model has {len(model.subgraphs)} subgraph(s)")

    # ── Ensure ADD opcode exists (shared across all subgraphs) ───────────────
    ADD_CODE = schema.BuiltinOperator.ADD  # 0 in standard schema
    add_opcode_idx = None
    for i, oc in enumerate(model.operatorCodes):
        bc = (
            oc.builtinCode
            if oc.builtinCode != 127
            else oc.deprecatedBuiltinCode
        )
        if bc == ADD_CODE:
            add_opcode_idx = i
            break

    if add_opcode_idx is None:
        oc = schema.OperatorCodeT()
        oc.builtinCode = ADD_CODE
        oc.deprecatedBuiltinCode = min(ADD_CODE, 127)
        oc.version = 1
        model.operatorCodes.append(oc)
        add_opcode_idx = len(model.operatorCodes) - 1
        print(f"Added ADD opcode at index {add_opcode_idx}")
    else:
        print(f"Reusing existing ADD opcode at index {add_opcode_idx}")

    # Track per-subgraph remapping for signature updates: {g_idx: (old, new)}
    subgraph_output_remap: dict[int, tuple[int, int]] = {}

    for g_idx, sg in enumerate(model.subgraphs):
        assert len(sg.outputs) == 1, (
            f"Subgraph[{g_idx}]: expected 1 output, got {len(sg.outputs)}"
        )
        orig_out_idx = sg.outputs[0]
        orig_out = sg.tensors[orig_out_idx]

        dtype = orig_out.type
        shape = list(orig_out.shape) if orig_out.shape is not None else []
        print(
            f"Subgraph[{g_idx}] original output: tensor[{orig_out_idx}] "
            f"name={orig_out.name} shape={shape} dtype={dtype}"
        )
        assert dtype == 0, f"Subgraph[{g_idx}]: expected FLOAT32 (0), got {dtype}"

        # ── 1. Scalar zero constant buffer (4 bytes) ─────────────────────────
        zero_buf = schema.BufferT()
        zero_buf.data = list(struct.pack("<f", 0.0))
        model.buffers.append(zero_buf)
        zero_buf_idx = len(model.buffers) - 1

        # ── 2. Scalar constant tensor (shape=[], FLOAT32) ─────────────────────
        zero_tensor = schema.TensorT()
        zero_tensor.name = f"densify_zero_const_{g_idx}".encode()
        zero_tensor.shape = []
        zero_tensor.type = 0  # FLOAT32
        zero_tensor.buffer = zero_buf_idx
        sg.tensors.append(zero_tensor)
        zero_tensor_idx = len(sg.tensors) - 1

        # ── 3. New output tensor (same shape as original, runtime-allocated) ──
        new_out = schema.TensorT()
        new_out.name = f"densify_add_out_{g_idx}".encode()
        new_out.shape = shape  # same shape as original; runtime will resize
        new_out.type = 0  # FLOAT32
        new_out.buffer = 0  # index 0 = empty, allocated at runtime
        sg.tensors.append(new_out)
        new_out_idx = len(sg.tensors) - 1

        # ── 4. ADD operator ───────────────────────────────────────────────────
        add_opts = schema.AddOptionsT()
        add_opts.fusedActivationFunction = 0  # NONE

        add_op = schema.OperatorT()
        add_op.opcodeIndex = add_opcode_idx
        add_op.inputs = [orig_out_idx, zero_tensor_idx]
        add_op.outputs = [new_out_idx]
        add_op.builtinOptionsType = schema.BuiltinOptions.AddOptions
        add_op.builtinOptions = add_opts
        sg.operators.append(add_op)

        # ── 5. Update subgraph output ─────────────────────────────────────────
        sg.outputs = [new_out_idx]

        subgraph_output_remap[g_idx] = (orig_out_idx, new_out_idx)
        print(
            f"Subgraph[{g_idx}]: ADD op [{orig_out_idx}(embed) + "
            f"{zero_tensor_idx}(zero)] -> {new_out_idx}(new_out)"
        )

    # ── Update all signature outputs, matched by subgraphIndex ───────────────
    sig_updates = 0
    if model.signatureDefs:
        for sd in model.signatureDefs:
            g_idx = sd.subgraphIndex
            if g_idx not in subgraph_output_remap:
                continue
            orig_out_idx, new_out_idx = subgraph_output_remap[g_idx]
            if sd.outputs:
                for out_sig in sd.outputs:
                    if out_sig.tensorIndex == orig_out_idx:
                        print(
                            f"Updating signature '{sd.signatureKey.decode()}' "
                            f"(subgraph {g_idx}) output "
                            f"'{out_sig.name.decode()}': "
                            f"{out_sig.tensorIndex} -> {new_out_idx}"
                        )
                        out_sig.tensorIndex = new_out_idx
                        sig_updates += 1

    print(f"Signature outputs updated: {sig_updates}")

    # ── Serialize ─────────────────────────────────────────────────────────────
    builder = flatbuffers.Builder(len(data) + 65536)
    packed = model.Pack(builder)
    builder.Finish(packed)
    output_bytes = bytes(builder.Output())

    output_path.write_bytes(output_bytes)
    print(f"\nWritten: {output_path}  ({len(output_bytes):,} bytes, "
          f"delta={len(output_bytes) - len(data):+d})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} INPUT.tflite [OUTPUT.tflite]")
        sys.exit(1)

    inp = pathlib.Path(sys.argv[1])
    out = (
        pathlib.Path(sys.argv[2])
        if len(sys.argv) > 2
        else inp.with_name(inp.stem + "_densified.tflite")
    )
    densify(inp, out)
