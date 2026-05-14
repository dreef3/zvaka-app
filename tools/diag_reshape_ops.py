#!/usr/bin/env python3
"""Diagnose Reshape ops in a TFLite model - check const vs dynamic shapes and new_shape population."""

import struct
import sys

MODEL = sys.argv[1] if len(sys.argv) > 1 else (
    "/home/ae/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9/export_work/model_quantized.tflite"
)

buf = bytearray(open(MODEL, "rb").read())


def r32(b, o):
    return struct.unpack_from("<i", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def vtable_field(b, tpos, fid):
    vt = tpos - r32(b, tpos)
    vsz = u16(b, vt)
    nfields = (vsz - 4) // 2
    if fid >= nfields:
        return None
    off = u16(b, vt + 4 + fid * 2)
    return None if off == 0 else tpos + off


def vec_i32(b, fpos):
    vp = fpos + r32(b, fpos)
    n = u32(b, vp)
    return [r32(b, vp + 4 + i * 4) for i in range(n)]


def vec_tables(b, fpos):
    vp = fpos + r32(b, fpos)
    n = u32(b, vp)
    return [vp + 4 + i * 4 + r32(b, vp + 4 + i * 4) for i in range(n)]


def vec_bytes(b, fpos):
    vp = fpos + r32(b, fpos)
    n = u32(b, vp)
    return bytes(b[vp + 4 : vp + 4 + n])


# Flatbuffers root: first 4 bytes is uint32 offset to root table from buf start
model_pos = u32(buf, 0)

# Model fields: version=0, operator_codes=1, subgraphs=2, description=3, buffers=4
oc_fpos = vtable_field(buf, model_pos, 1)
sg_fpos = vtable_field(buf, model_pos, 2)
buf_fpos = vtable_field(buf, model_pos, 4)

operator_code_tables = vec_tables(buf, oc_fpos)
subgraph_tables = vec_tables(buf, sg_fpos)
buffer_tables = vec_tables(buf, buf_fpos)


def get_builtin_code(b, oc_pos):
    f3 = vtable_field(b, oc_pos, 3)
    if f3:
        return r32(b, f3)
    f0 = vtable_field(b, oc_pos, 0)
    if f0:
        return struct.unpack_from("<b", b, f0)[0]
    return 0


def get_buffer_data(b, buf_pos):
    f = vtable_field(b, buf_pos, 0)
    if f is None:
        return None
    return vec_bytes(b, f)


RESHAPE = 22

total_reshape = 0
has_const_shape = 0
has_dyn_shape = 0
new_shape_populated = 0
new_shape_empty = 0
example_shapes = []

for sg_idx, sg_pos in enumerate(subgraph_tables):
    tensor_fpos = vtable_field(buf, sg_pos, 0)
    op_fpos = vtable_field(buf, sg_pos, 3)
    if tensor_fpos is None or op_fpos is None:
        continue
    tensor_tables = vec_tables(buf, tensor_fpos)
    op_tables = vec_tables(buf, op_fpos)

    for op_pos in op_tables:
        oc_idx_fpos = vtable_field(buf, op_pos, 0)
        if oc_idx_fpos is None:
            continue
        oc_idx = u32(buf, oc_idx_fpos)
        if oc_idx >= len(operator_code_tables):
            continue
        bc = get_builtin_code(buf, operator_code_tables[oc_idx])
        if bc != RESHAPE:
            continue

        total_reshape += 1
        inp_fpos = vtable_field(buf, op_pos, 1)
        if inp_fpos is None:
            continue
        inputs = vec_i32(buf, inp_fpos)

        if len(inputs) < 2:
            continue
        shape_tensor_idx = inputs[1]

        if shape_tensor_idx < 0 or shape_tensor_idx >= len(tensor_tables):
            continue

        st_pos = tensor_tables[shape_tensor_idx]
        buf_idx_fpos = vtable_field(buf, st_pos, 2)
        if buf_idx_fpos is None:
            continue
        buf_idx = u32(buf, buf_idx_fpos)

        if buf_idx < len(buffer_tables):
            data = get_buffer_data(buf, buffer_tables[buf_idx])
            is_const = data is not None and len(data) > 0
        else:
            is_const = False

        if is_const:
            has_const_shape += 1
            shape_vals = [r32(bytearray(data), i * 4) for i in range(len(data) // 4)]
            if len(example_shapes) < 8:
                example_shapes.append((sg_idx, shape_tensor_idx, shape_vals))
        else:
            has_dyn_shape += 1

        # Check ReshapeOptions.new_shape (builtin_options=field 4 in Operator)
        bo_fpos = vtable_field(buf, op_pos, 4)
        if bo_fpos:
            ro_pos = bo_fpos + r32(buf, bo_fpos)
            ns_fpos = vtable_field(buf, ro_pos, 0)
            if ns_fpos:
                ns = vec_i32(buf, ns_fpos)
                if ns:
                    new_shape_populated += 1
                else:
                    new_shape_empty += 1
            else:
                new_shape_empty += 1
        else:
            new_shape_empty += 1

print(f"Model: {MODEL}")
print(f"Total Reshape ops:        {total_reshape}")
print(f"  Shape from const buffer:{has_const_shape}")
print(f"  Shape from dynamic input:{has_dyn_shape}")
print(f"  new_shape populated:    {new_shape_populated}")
print(f"  new_shape empty:        {new_shape_empty}")
print("Example const shapes (sg, tensor, values):")
for ex in example_shapes:
    print(f"  sg={ex[0]:3d}  t={ex[1]:4d}  shape={ex[2]}")
