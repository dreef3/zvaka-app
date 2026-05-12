#!/usr/bin/env python3
"""
add_reshape_new_shape.py

Append-and-redirect TFLite patcher:
  For every Reshape op, add builtin_options -> ReshapeOptions{new_shape=output.shape}
  while keeping the existing inputs/outputs tensor index lists intact.

LiteRT reads ALL flatbuffer field refs as UNSIGNED (uoffset_t / uint32_t).
Negative relative offsets (pointing to lower addresses) are zero-extended to
64 bits and cause address-arithmetic overflow -> SIGSEGV in ParseNodes.

Strategy: for each Reshape op, write a NEW Operator table at the end of the
buffer with ALL data that it references written AFTER it (forward references,
always positive).  Concretely, for each new op we append in this order:

  [Operator vtable]
  [Operator table object, 20 bytes, with forward-ref placeholders]
  [ReshapeOptions vtable]
  [ReshapeOptions table object, 8 bytes, with forward-ref placeholder]
  [new_shape vector]
  [outputs tensor-index vector  (copy of original)]
  [inputs  tensor-index vector  (copy of original)]

All placeholder fields are fixed up after the referenced data is written,
so every uoffset_t field value is positive.

The ops-vector slot for each Reshape op is then patched in-place to point to
the new Operator table (also a positive forward offset because the new table is
appended beyond the original ops-vector position).

Usage: python3 add_reshape_new_shape.py [in.tflite [out.tflite]]
"""

import struct
import sys
from typing import Optional

RESHAPE_BC = 22        # BuiltinOperator.RESHAPE
BO_TYPE_RESHAPE = 17   # BuiltinOptions union discriminant for ReshapeOptions (≠ operator code)

# ---------------------------------------------------------------------------
# Low-level flatbuffer reader
# ---------------------------------------------------------------------------
def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
def _u16(b, o): return struct.unpack_from("<H", b, o)[0]
def _s8(b, o):  return struct.unpack_from("<b", b, o)[0]

def _vf(b, tp, fid):
    """Return absolute position of field fid in table at tp, or None."""
    vt = tp - _r32(b, tp)
    vsz = _u16(b, vt)
    nf = (vsz - 4) // 2
    if fid >= nf: return None
    off = _u16(b, vt + 4 + fid * 2)
    return None if off == 0 else tp + off

def _vec_tables(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [vp + 4 + i*4 + _r32(b, vp + 4 + i*4) for i in range(n)]

def _vec_i32(b, fpos):
    """Read [int] vector via a field position (handles the relative offset indirection)."""
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [_r32(b, vp + 4 + i*4) for i in range(n)]

def _bc(b, oc_pos):
    f3 = _vf(b, oc_pos, 3)
    if f3: return _r32(b, f3)
    f0 = _vf(b, oc_pos, 0)
    if f0: return _s8(b, f0)
    return 0

def _shape_from_buffer(b, tensor_pos, buffer_tables):
    """Read int32 values from a constant tensor's data buffer (for Reshape shape tensors)."""
    buf_id_fp = _vf(b, tensor_pos, 2)   # Tensor field 2 = buffer
    if buf_id_fp is None:
        return []
    buf_id = _u32(b, buf_id_fp)
    if buf_id == 0 or buf_id >= len(buffer_tables):
        return []
    bt = buffer_tables[buf_id]
    data_fp = _vf(b, bt, 0)             # Buffer field 0 = data ([ubyte])
    if data_fp is None:
        return []
    vp = data_fp + _r32(b, data_fp)     # absolute position of ubyte vector
    nbytes = _u32(b, vp)
    if nbytes == 0 or nbytes % 4 != 0:
        return []
    return [_r32(b, vp + 4 + i * 4) for i in range(nbytes // 4)]

# ---------------------------------------------------------------------------
# Appender: write new flatbuffer objects at the end of the mutable buffer
# ---------------------------------------------------------------------------
class Appender:
    def __init__(self, buf: bytearray):
        self.buf = buf

    def pos(self): return len(self.buf)

    def _align(self, n):
        r = len(self.buf) % n
        if r: self.buf += b'\x00' * (n - r)

    def _u8(self, v):  self.buf += struct.pack("<B", v)
    def _u16(self, v): self.buf += struct.pack("<H", v)
    def _i32(self, v): self.buf += struct.pack("<i", v)
    def _u32(self, v): self.buf += struct.pack("<I", v)
    def put_i32_at(self, off, v): struct.pack_into("<i", self.buf, off, v)
    def put_u32_at(self, off, v): struct.pack_into("<I", self.buf, off, v)

    def write_complete_reshape_op(
        self,
        inputs_values: list,
        outputs_values: list,
        new_shape: list,
    ) -> int:
        """
        Append a complete Reshape Operator with all data in forward (positive-offset) order.

        TFLite Operator vtable layout used here (matches the schema and real model ops):
          vsz=14, obj_sz=20, 5 field slots:
          field[0] (opcode_index, uint32): ABSENT → defaults to 0 (= Reshape opcode)
          field[1] (inputs,  uoffset):     obj_off=16
          field[2] (outputs, uoffset):     obj_off=12
          field[3] (builtin_options_type, uint8): obj_off=11
          field[4] (builtin_options, uoffset):    obj_off=4

        Append order guarantees all uoffset_t fields are positive:
          [Operator vtable][Operator table][ReshapeOptions vtable]
          [ReshapeOptions table][new_shape vec][outputs vec][inputs vec]

        Returns the absolute position of the Operator table.
        """
        # --- Operator vtable ---
        self._align(2)
        op_vt = self.pos()
        self._u16(14)   # vtable size: 4 + 5*2
        self._u16(20)   # object size
        self._u16(0)    # field[0] (opcode_index): absent → default 0 = Reshape
        self._u16(16)   # field[1] (inputs)
        self._u16(12)   # field[2] (outputs)
        self._u16(11)   # field[3] (builtin_options_type)
        self._u16(4)    # field[4] (builtin_options)

        # --- Operator table (20 bytes) ---
        self._align(4)
        op_tb = self.pos()
        self._i32(op_tb - op_vt)    # soffset: positive (vtable written just before)

        f4_slot = self.pos()         # obj_off=4: builtin_options uoffset placeholder
        self._u32(0)

        self._u8(0); self._u8(0); self._u8(0)   # obj_off=8-10: padding
        self._u8(BO_TYPE_RESHAPE)                # obj_off=11: builtin_options_type=22

        f2_slot = self.pos()         # obj_off=12: outputs uoffset placeholder
        self._u32(0)

        f1_slot = self.pos()         # obj_off=16: inputs uoffset placeholder
        self._u32(0)
        # Total operator object: 4+4+3+1+4+4 = 20 bytes ✓

        # --- ReshapeOptions vtable ---
        self._align(2)
        ro_vt = self.pos()
        self._u16(6)    # vtable size: 4 + 1*2
        self._u16(8)    # object size: soffset(4) + new_shape_ref(4)
        self._u16(4)    # field[0] (new_shape): obj_off=4

        # --- ReshapeOptions table (8 bytes) ---
        self._align(4)
        ro_tb = self.pos()
        self._i32(ro_tb - ro_vt)    # soffset: positive

        ro_f0_slot = self.pos()      # obj_off=4: new_shape uoffset placeholder
        self._u32(0)

        # --- new_shape vector ---
        self._align(4)
        ns_pos = self.pos()
        self._u32(len(new_shape))
        for v in new_shape:
            self._i32(v)

        # --- outputs tensor-index vector copy ---
        self._align(4)
        out_pos = self.pos()
        self._u32(len(outputs_values))
        for v in outputs_values:
            self._i32(v)

        # --- inputs tensor-index vector copy ---
        self._align(4)
        inp_pos = self.pos()
        self._u32(len(inputs_values))
        for v in inputs_values:
            self._i32(v)

        # Fix up all forward-reference placeholders (all values guaranteed positive)
        self.put_u32_at(ro_f0_slot, ns_pos  - ro_f0_slot)   # new_shape
        self.put_u32_at(f4_slot,    ro_tb   - f4_slot)       # builtin_options
        self.put_u32_at(f2_slot,    out_pos - f2_slot)        # outputs
        self.put_u32_at(f1_slot,    inp_pos - f1_slot)        # inputs

        return op_tb


# ---------------------------------------------------------------------------
# Main patching function
# ---------------------------------------------------------------------------
def patch_model(buf_in: bytearray) -> bytearray:
    buf = bytearray(buf_in)
    app = Appender(buf)

    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)
    buf_fpos  = _vf(buf, model_pos, 4)   # Model field 4 = buffers

    oc_tables     = _vec_tables(buf, oc_fpos)
    sg_tables     = _vec_tables(buf, sg_fpos)
    buffer_tables = _vec_tables(buf, buf_fpos) if buf_fpos else []

    total_reshape = 0

    for sg_pos in sg_tables:
        t_fpos  = _vf(buf, sg_pos, 0)
        op_fpos = _vf(buf, sg_pos, 3)
        if op_fpos is None:
            continue

        tensor_tables = _vec_tables(buf, t_fpos) if t_fpos else []
        op_tables     = _vec_tables(buf, op_fpos)

        # Absolute address of the original ops-vector and its element array.
        ops_vec_abs   = op_fpos + _r32(buf, op_fpos)
        ops_elem_base = ops_vec_abs + 4  # first slot (past the count word)

        for i, op_pos in enumerate(op_tables):
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == RESHAPE_BC):
                continue

            # Read outputs tensor indices; use first output tensor's shape.
            out_fpos = _vf(buf, op_pos, 2)
            outputs_values = _vec_i32(buf, out_fpos) if out_fpos else []
            out_shape = []
            if outputs_values and 0 <= outputs_values[0] < len(tensor_tables):
                ts_fpos = _vf(buf, tensor_tables[outputs_values[0]], 0)
                if ts_fpos:
                    out_shape = _vec_i32(buf, ts_fpos)

            # Read inputs tensor indices.
            inp_fpos = _vf(buf, op_pos, 1)
            inputs_values = list(_vec_i32(buf, inp_fpos)) if inp_fpos else []

            # If out_shape is empty (dynamic or scalar output), fall back to reading
            # the shape from inputs[1]'s constant buffer.
            if not out_shape and len(inputs_values) > 1 and inputs_values[1] >= 0:
                shape_tidx = inputs_values[1]
                if shape_tidx < len(tensor_tables):
                    out_shape = _shape_from_buffer(buf, tensor_tables[shape_tidx], buffer_tables)

            # If we still have no shape, skip patching this op to avoid an empty
            # new_shape that would cause a NeuronAdapter "Invalid number of in operands".
            if not out_shape:
                print(f"  WARNING: skipping Reshape op (no shape found), sg_pos={sg_pos}, i={i}",
                      file=sys.stderr)
                continue

            # Zero out inputs[1] so the SDK reads shape from builtin_options.new_shape only.
            if len(inputs_values) > 1:
                inputs_values[1] = -1

            # Append new Operator + ReshapeOptions + vectors (all forward refs).
            op_abs = app.write_complete_reshape_op(inputs_values, outputs_values, out_shape)

            # Patch the existing ops-vector slot in-place.
            # slot_pos is in the original (low-address) ops-vector;
            # op_abs is appended at the end of the buffer (higher address).
            # Therefore op_abs - slot_pos is always POSITIVE.
            slot_pos = ops_elem_base + i * 4
            struct.pack_into("<i", buf, slot_pos, op_abs - slot_pos)

            total_reshape += 1

    print(f"Reshaped ops patched: {total_reshape}", file=sys.stderr)
    return buf


def main():
    in_path  = sys.argv[1] if len(sys.argv) > 1 else (
        "/home/ae/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9/export_work/model_quantized.tflite"
    )
    out_path = sys.argv[2] if len(sys.argv) > 2 else in_path.replace(".tflite", ".ns_patched.tflite")

    print(f"Input:  {in_path}")
    print(f"Output: {out_path}")

    with open(in_path, "rb") as f:
        raw = bytearray(f.read())

    print(f"Size: {len(raw):,} bytes")
    patched = patch_model(raw)
    print(f"Patched size: {len(patched):,} bytes  (+{len(patched)-len(raw):,})")

    with open(out_path, "wb") as f:
        f.write(patched)

    print(f"Written: {out_path}")


if __name__ == "__main__":
    main()
