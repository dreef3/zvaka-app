#!/usr/bin/env python3
"""Fix dynamic RESHAPE ops in TFLite model to static form.

TFLite RESHAPE ops can have:
  - Input 0: tensor to reshape
  - Input 1: new shape as tensor (dynamic tensor computed at runtime)

NeuronAdapter v9 fails with "MapReshapeOps: Output shape as inputs not supported"
when input 1 is present. It requires the shape in the ReshapeOptions builtin options.

Strategy:
1. Run the TFLite model with dummy inputs via the ai_edge_litert Interpreter
2. After each invoke(), capture the INT32 shape tensor values for all RESHAPE ops
3. Bake those values into the model as constant tensors
4. Convert the RESHAPE ops to use ReshapeOptions builtin options

Usage:
  python fix_dynamic_reshape.py input.tflite output.tflite
"""
import sys
import struct
import numpy as np
import flatbuffers
from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.interpreter import Interpreter


RESHAPE_OPCODE = 22  # BuiltinOperator.RESHAPE


def collect_shape_values_by_inference(model_path: str) -> dict:
    """Run the model with dummy inputs and collect shape tensor values.

    Returns: dict mapping (subgraph_idx, tensor_idx) → numpy array of int32 shape values.
    """
    print(f"Loading model for shape inference: {model_path}")
    interp = Interpreter(model_path=model_path)
    interp.allocate_tensors()

    input_details = interp.get_input_details()
    print(f"  {len(input_details)} inputs")

    # Create dummy inputs: zeros of the correct dtype and shape
    for d in input_details:
        shape = d['shape']
        dtype = d['dtype']
        dummy = np.zeros(shape, dtype=dtype)
        interp.set_tensor(d['index'], dummy)

    print("  Running inference (CPU, may take a moment)...")
    interp.invoke()
    print("  Inference complete")

    # Collect all INT32 tensor values (shape tensors are INT32)
    tensor_details = interp.get_tensor_details()
    shape_values = {}
    for d in tensor_details:
        if d['dtype'] == np.int32:
            try:
                val = interp.get_tensor(d['index'])
                if val is not None and val.ndim == 1 and 1 <= len(val) <= 8:
                    # Subgraph 0 only for now (single-subgraph interpreter)
                    shape_values[(0, d['index'])] = val.copy()
            except Exception:
                pass

    print(f"  Collected {len(shape_values)} INT32 shape tensor values")
    return shape_values


def populate_external_buffers(model, original_buf: bytearray) -> int:
    """Copy weight data from external storage (offset/size) into buffer.data fields.
    Also zeroes offset/size so the repacked file uses only inline data."""
    populated = 0
    for buf_obj in model.buffers:
        if buf_obj.data is not None and len(buf_obj.data) > 0:
            # Already inline — still clear external fields to avoid double-read.
            if hasattr(buf_obj, "offset"): buf_obj.offset = 0
            if hasattr(buf_obj, "size"): buf_obj.size = 0
            continue
        offset = getattr(buf_obj, "offset", 0) or 0
        size = getattr(buf_obj, "size", 0) or 0
        if offset > 0 and size > 0:
            buf_obj.data = bytearray(original_buf[offset : offset + size])
            buf_obj.offset = 0
            buf_obj.size = 0
            populated += 1
    return populated


def fix_dynamic_reshape(input_path: str, output_path: str) -> None:
    print(f"Loading model: {input_path}")
    with open(input_path, "rb") as f:
        buf = bytearray(f.read())
    print(f"  Size: {len(buf):,} bytes")

    model = schema.ModelT.InitFromPackedBuf(buf, 0)

    # Models using external buffer storage have data=None; copy from file bytes.
    n_populated = populate_external_buffers(model, buf)
    if n_populated:
        print(f"  Populated {n_populated} external buffers from file bytes")
    n_subgraphs = len(model.subgraphs)
    print(f"  Subgraphs: {n_subgraphs}")

    # Find RESHAPE opcode index
    reshape_opcode_idx = None
    for i, op_code in enumerate(model.operatorCodes):
        if op_code.builtinCode == RESHAPE_OPCODE:
            reshape_opcode_idx = i
            break

    if reshape_opcode_idx is None:
        print("No RESHAPE opcode — nothing to fix.")
        return

    print(f"  RESHAPE opcode index: {reshape_opcode_idx}")

    # Collect per-subgraph shape tensor values
    # We'll process one subgraph at a time using subgraph-specific inference
    # For simplicity, scan all subgraphs and try to get shape tensor values
    # from their buffer data first; fall back to inference for empty ones.

    # Build a map of buffer_idx → data for constant tensors we discover
    # by analyzing the computation graph.

    total_fixed = 0
    total_skipped = 0

    for sg_idx, subgraph in enumerate(model.subgraphs):
        if not subgraph.operators:
            continue

        for op in subgraph.operators:
            if op.opcodeIndex != reshape_opcode_idx:
                continue
            if len(op.inputs) < 2 or op.inputs[1] == -1:
                continue

            shape_tensor_idx = op.inputs[1]
            shape_tensor = subgraph.tensors[shape_tensor_idx]
            buf_idx = shape_tensor.buffer

            # Check buffer data
            buffer_data = model.buffers[buf_idx].data
            if buffer_data is not None and len(buffer_data) > 0:
                n_dims = len(buffer_data) // 4
                new_shape = list(struct.unpack(f"<{n_dims}i", bytes(buffer_data)))
                reshape_opts = schema.ReshapeOptionsT()
                reshape_opts.newShape = new_shape
                op.builtinOptionsType = schema.BuiltinOptions.ReshapeOptions
                op.builtinOptions = reshape_opts
                # Keep input 1 (shape tensor) — NeuronAdapter requires 2 inputs for RESHAPE.
                # (Previously removed for v8 MapReshapeOps; now fixed via binary NOP patch.)
                total_fixed += 1
                continue

            # No buffer data — need to determine shape from tensor shape info
            # The output tensor of the RESHAPE op has the shape we need
            output_tensor_idx = op.outputs[0]
            output_tensor = subgraph.tensors[output_tensor_idx]
            output_shape = list(output_tensor.shape) if output_tensor.shape is not None else None

            # output_shape=[] means reshape to scalar, which is valid
            if output_shape is not None and all(s > 0 for s in output_shape):
                # Bake the static shape into the shape tensor's buffer (converts dynamic→constant).
                # Keep BOTH inputs so NeuronAdapter sees 2-input RESHAPE as expected.
                shape_bytes = struct.pack(f"<{len(output_shape)}i", *output_shape)
                model.buffers[buf_idx].data = bytearray(shape_bytes)

                reshape_opts = schema.ReshapeOptionsT()
                reshape_opts.newShape = output_shape
                op.builtinOptionsType = schema.BuiltinOptions.ReshapeOptions
                op.builtinOptions = reshape_opts
                total_fixed += 1
            else:
                print(f"  sg[{sg_idx}] op: SKIP — shape_tensor={shape_tensor_idx}, output_shape={output_shape}")
                total_skipped += 1

    print(f"\nFixed: {total_fixed} RESHAPE ops, Skipped: {total_skipped}")

    if total_fixed == 0:
        print("Nothing fixed.")
        return

    print(f"Packing to {output_path}...")
    builder = flatbuffers.Builder(len(buf) + 1024 * 1024)
    packed = model.Pack(builder)
    builder.Finish(packed)
    out_bytes = bytes(builder.Output())

    # Manually insert TFL3 file identifier at bytes [4:8].
    # flatbuffers layout: [root_offset(4)] [file_id(4)] [data...]
    # Finish() wrote [root_offset(4)] [data...]; shift root_offset by 4 to account
    # for the identifier, then splice in the identifier.
    root_offset = int.from_bytes(out_bytes[:4], "little")
    out_bytes = (root_offset + 4).to_bytes(4, "little") + b"TFL3" + out_bytes[4:]

    with open(output_path, "wb") as f:
        f.write(out_bytes)
    print(f"Wrote {len(out_bytes):,} bytes to {output_path}")
    print(f"Output header: {out_bytes[:4].hex()} id={out_bytes[4:8]!r}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} input.tflite output.tflite")
        sys.exit(1)
    fix_dynamic_reshape(sys.argv[1], sys.argv[2])
