#!/usr/bin/env python3
"""
fix_pack_scalar_inputs.py — Restore PACK inputs to scalar (shape=[]) so that
PACK(N × shape=[]) → shape=[N] (1D), satisfying DYNAMIC_UPDATE_SLICE.

Problem:
  Pass 0 of add_reshape_new_shape.py patches all rank-0 tensors (shape=[]) to
  shape=[1].  PACK ops that stack N scalar position indices produce shape=[N,1]
  (2D) instead of [N] (1D), causing DYNAMIC_UPDATE_SLICE to fail with
  "NumDimensions(start_indices) == 1 was not true".

Two categories of PACK inputs need to be fixed:

  1. Constant scalar tensors (no op produces them, offset-based buffer):
     Just zero out the declared shape vector count → tensor becomes rank-0.
     Element count stays 1 (scalar has 1 element), so data size is unchanged.

  2. Intermediate tensors produced by RESHAPE-to-scalar ops:
     RESHAPE reads a shape-spec tensor (inputs[1]) whose buffer currently
     contains [1] (because Pass 0+1 embedded it from the [1]-patched output).
     We zero the byte-count of the shape-spec buffer → RESHAPE sees an empty
     shape vector → produces a scalar output at runtime.
     We also zero the shape-spec tensor's declared shape for consistency.

Apply to the compiled .tflite AFTER AOT compilation and AFTER restore_fc_bias,
BEFORE bundling into .litertlm.

Usage:
  python3 fix_pack_scalar_inputs.py in.tflite out.tflite
"""

import struct
import sys
import pathlib

# ---------------------------------------------------------------------------
# Flatbuffer helpers
# ---------------------------------------------------------------------------
def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
def _u16(b, o): return struct.unpack_from("<H", b, o)[0]
def _s8(b, o):  return struct.unpack_from("<b", b, o)[0]

def _vf(b, tp, fid):
    vt  = tp - _r32(b, tp)
    vsz = _u16(b, vt)
    nf  = (vsz - 4) // 2
    if fid >= nf:
        return None
    off = _u16(b, vt + 4 + fid * 2)
    return None if off == 0 else tp + off

def _vec_tables(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [vp + 4 + i * 4 + _r32(b, vp + 4 + i * 4) for i in range(n)]

def _vec_i32(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [_r32(b, vp + 4 + i * 4) for i in range(n)]

def _bc(b, oc_pos):
    f3 = _vf(b, oc_pos, 3)
    if f3:
        return _r32(b, f3)
    f0 = _vf(b, oc_pos, 0)
    if f0:
        return _s8(b, f0)
    return 0

PACK_BC   = 83   # BuiltinOperator.PACK
RESHAPE_BC = 22  # BuiltinOperator.RESHAPE


# ---------------------------------------------------------------------------
# Fix function
# ---------------------------------------------------------------------------
def fix_pack_scalar_inputs(buf_in: bytes | bytearray) -> tuple[bytearray, int]:
    """
    Return (patched_bytes, count) where count is the number of tensors fixed.
    """
    buf = bytearray(buf_in)

    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)
    buf_fpos  = _vf(buf, model_pos, 4)

    if oc_fpos is None or sg_fpos is None:
        return buf, 0

    oc_tables     = _vec_tables(buf, oc_fpos)
    sg_tables     = _vec_tables(buf, sg_fpos)
    buffer_tables = _vec_tables(buf, buf_fpos) if buf_fpos else []

    total_fixed = 0

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

        # Track already-fixed positions to avoid double-counting shared vectors
        zeroed_shape_vecs: set[int] = set()
        zeroed_buf_data: set[int]   = set()

        def _zero_shape_vec(tt: int) -> bool:
            """
            Zero the declared shape of tensor table tt (set count to 0 → shape=[]).
            Returns True if a new position was zeroed.
            """
            ts_fpos = _vf(buf, tt, 0)
            if ts_fpos is None:
                return False
            shape_vp = ts_fpos + _r32(buf, ts_fpos)
            if shape_vp in zeroed_shape_vecs:
                return False
            count = _u32(buf, shape_vp)
            if count == 0:
                zeroed_shape_vecs.add(shape_vp)
                return False  # already scalar
            struct.pack_into("<I", buf, shape_vp, 0)
            zeroed_shape_vecs.add(shape_vp)
            return True

        def _zero_embedded_buffer_count(buf_id: int) -> bool:
            """
            Zero the byte-count of an embedded buffer's data vector so that
            the shape-spec is empty → RESHAPE produces scalar output.
            Returns True if zeroed.
            """
            if buf_id == 0 or buf_id >= len(buffer_tables):
                return False
            bt = buffer_tables[buf_id]
            f0 = _vf(buf, bt, 0)  # field 0 = data ([ubyte])
            if f0 is None:
                return False  # not an embedded buffer
            data_vp = f0 + _r32(buf, f0)
            if data_vp in zeroed_buf_data:
                return False
            count = _u32(buf, data_vp)
            if count == 0:
                zeroed_buf_data.add(data_vp)
                return False
            struct.pack_into("<I", buf, data_vp, 0)
            zeroed_buf_data.add(data_vp)
            return True

        for op_pos in op_tables:
            # Only process PACK ops
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

                # Check if shape is currently [1] (1D with dim=1)
                ts_fpos = _vf(buf, tt, 0)
                if ts_fpos is None:
                    continue
                shape_vp = ts_fpos + _r32(buf, ts_fpos)
                if _u32(buf, shape_vp) != 1:
                    continue  # not shape=[1]
                if _r32(buf, shape_vp + 4) != 1:
                    continue  # first dim is not 1

                prod_op = producer.get(tidx)

                if prod_op is not None:
                    # This PACK input is produced at runtime by another op.
                    # If it's produced by a RESHAPE-to-scalar, fix its shape spec.
                    prod_oc_idx_fpos = _vf(buf, prod_op, 0)
                    prod_oc_idx = (0 if prod_oc_idx_fpos is None
                                   else _u32(buf, prod_oc_idx_fpos))
                    if not (prod_oc_idx < len(oc_tables)
                            and _bc(buf, oc_tables[prod_oc_idx]) == RESHAPE_BC):
                        continue  # non-RESHAPE producer — leave as-is

                    # Find RESHAPE's shape-spec tensor (inputs[1])
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

                    # Zero the shape-spec buffer (makes RESHAPE produce scalar)
                    spec_bid_fpos = _vf(buf, spec_tt, 2)
                    if spec_bid_fpos is None:
                        continue
                    spec_buf_id = _u32(buf, spec_bid_fpos)
                    if _zero_embedded_buffer_count(spec_buf_id):
                        print(f"  zeroed shape-spec buffer {spec_buf_id} for "
                              f"RESHAPE→t{tidx} (PACK input)", file=sys.stderr)
                        total_fixed += 1

                    # Also zero the shape-spec tensor's declared shape
                    _zero_shape_vec(spec_tt)

                    # Also zero the PACK input tensor's declared shape
                    _zero_shape_vec(tt)

                else:
                    # Constant PACK input (no producer) — zero its declared shape.
                    if _zero_shape_vec(tt):
                        print(f"  zeroed declared shape of constant t{tidx} "
                              f"(PACK scalar input)", file=sys.stderr)
                        total_fixed += 1

    return buf, total_fixed


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------
def main() -> int:
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} in.tflite out.tflite", file=sys.stderr)
        return 1

    in_path  = pathlib.Path(sys.argv[1])
    out_path = pathlib.Path(sys.argv[2])

    result, count = fix_pack_scalar_inputs(in_path.read_bytes())
    out_path.write_bytes(result)
    print(f"PACK scalar inputs fixed: {count} → {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
