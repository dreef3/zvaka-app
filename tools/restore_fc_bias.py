#!/usr/bin/env python3
"""
restore_fc_bias.py — Undo Pass 2 of add_reshape_new_shape.py in a .tflite.

Pass 2 sets FC ops' absent bias (inputs[2]=-1) to inputs[0] so that
NeuronAdapter's DLA float32 type check passes during AOT compilation.
After compilation those FC ops run on the TFLite CPU kernel, which validates
  NumElements(bias) == SizeOfDimension(filter, 0)
and fails because inputs[0] (the data tensor) has the wrong shape.

This script restores inputs[2] = -1 for every FC op where inputs[2] == inputs[0]
(the unique Pass 2 signature).  Apply it to the compiled .tflite AFTER AOT
compilation, BEFORE bundling into .litertlm.

Usage:
  python3 restore_fc_bias.py in.tflite out.tflite
"""

import struct
import sys
import pathlib

# ---------------------------------------------------------------------------
# Flatbuffer helpers (mirrored from add_reshape_new_shape.py)
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

def _bc(b, oc_pos):
    f3 = _vf(b, oc_pos, 3)
    if f3:
        return _r32(b, f3)
    f0 = _vf(b, oc_pos, 0)
    if f0:
        return _s8(b, f0)
    return 0

FC_BC = 9  # BuiltinOperator.FULLY_CONNECTED

# ---------------------------------------------------------------------------
# Restore function
# ---------------------------------------------------------------------------
def restore_fc_bias(buf_in: bytes | bytearray) -> tuple[bytearray, int]:
    """
    Return (patched_bytes, count) where count is the number of FC ops restored.
    Mutates a copy — does not modify buf_in.
    """
    buf = bytearray(buf_in)

    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)

    if oc_fpos is None or sg_fpos is None:
        return buf, 0

    oc_tables = _vec_tables(buf, oc_fpos)
    sg_tables = _vec_tables(buf, sg_fpos)

    total_restored = 0

    for sg_pos in sg_tables:
        op_fpos = _vf(buf, sg_pos, 3)
        if op_fpos is None:
            continue
        op_tables = _vec_tables(buf, op_fpos)

        for op_pos in op_tables:
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == FC_BC):
                continue

            inp_fpos = _vf(buf, op_pos, 1)
            if inp_fpos is None:
                continue
            inp_vec_pos = inp_fpos + _r32(buf, inp_fpos)
            inp_count   = _u32(buf, inp_vec_pos)

            if inp_count < 3:
                continue

            bias_slot = inp_vec_pos + 4 + 2 * 4   # inputs[2]
            data_slot = inp_vec_pos + 4             # inputs[0]

            bias_idx = _r32(buf, bias_slot)
            data_idx = _r32(buf, data_slot)

            if bias_idx == data_idx:
                struct.pack_into("<i", buf, bias_slot, -1)
                total_restored += 1
                print(f"  restored FC inputs[2]=-1 (was tensor {bias_idx})",
                      file=sys.stderr)

    return buf, total_restored


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------
def main() -> int:
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} in.tflite out.tflite", file=sys.stderr)
        return 1

    in_path  = pathlib.Path(sys.argv[1])
    out_path = pathlib.Path(sys.argv[2])

    result, count = restore_fc_bias(in_path.read_bytes())
    out_path.write_bytes(result)
    print(f"FC bias slots restored: {count} → {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
