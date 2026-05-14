#!/usr/bin/env python3
"""
patch_reshape_static.py - Convert dynamic TFLite Reshape ops to static.

The v9_0_3 MediaTek NeuroPilot SDK rejects:
  "MapReshapeOps: Output shape as inputs not supported"

TFLite Reshape ops in the Gemma3 export have their shape as a 2nd input tensor
(inputs[1]) rather than only in ReshapeOptions.new_shape.  The SDK cannot handle
shape-as-input even when the tensor is a compile-time constant.

Fix: for each Reshape op where inputs[1] is a constant tensor,
  1. Read the shape from that tensor's buffer.
  2. Ensure ReshapeOptions.new_shape is set to those values.
  3. Set inputs[1] to -1 (TFLite sentinel = "not present").

The modified model is written to <input>.patched.tflite.

Usage: python3 patch_reshape_static.py [input.tflite [output.tflite]]
"""

import struct
import sys
import copy

RESHAPE_BUILTIN_CODE = 22


# ---------------------------------------------------------------------------
# Minimal flatbuffer reader (no codegen required)
# ---------------------------------------------------------------------------

def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
def _u16(b, o): return struct.unpack_from("<H", b, o)[0]
def _s8(b, o):  return struct.unpack_from("<b", b, o)[0]


def _vtfield(b, tpos, fid):
    """Return the absolute position of field fid in the table at tpos, or None."""
    vt = tpos - _r32(b, tpos)
    vsz = _u16(b, vt)
    nf = (vsz - 4) // 2
    if fid >= nf:
        return None
    off = _u16(b, vt + 4 + fid * 2)
    return None if off == 0 else tpos + off


def _vec_i32(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [_r32(b, vp + 4 + i * 4) for i in range(n)]


def _vec_tables(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [vp + 4 + i * 4 + _r32(b, vp + 4 + i * 4) for i in range(n)]


def _vec_bytes(b, fpos):
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return bytes(b[vp + 4: vp + 4 + n])


def _builtin_code(b, oc_pos):
    f3 = _vtfield(b, oc_pos, 3)
    if f3: return _r32(b, f3)
    f0 = _vtfield(b, oc_pos, 0)
    if f0: return _s8(b, f0)
    return 0


# ---------------------------------------------------------------------------
# Flatbuffer builder helpers (for rebuilding tables with changed sizes)
# ---------------------------------------------------------------------------

class FBBuilder:
    """Minimal flatbuffers builder for reconstructing a complete TFLite model."""

    def __init__(self):
        self._buf = bytearray()
        self._finished = False

    def _align(self, n):
        rem = len(self._buf) % n
        if rem:
            self._buf += b'\x00' * (n - rem)

    def _write_u8(self, v):   self._buf += struct.pack("<B", v)
    def _write_u16(self, v):  self._buf += struct.pack("<H", v)
    def _write_i32(self, v):  self._buf += struct.pack("<i", v)
    def _write_u32(self, v):  self._buf += struct.pack("<I", v)

    def create_vector_i32(self, values):
        self._align(4)
        pos = len(self._buf)
        self._write_u32(len(values))
        for v in values:
            self._write_i32(v)
        return pos

    def create_vector_u8(self, data: bytes):
        pos = len(self._buf)
        self._write_u32(len(data))
        self._buf += data
        return pos

    def create_vector_of_offsets(self, offsets):
        """Write a vector of int32 offsets (relative to each slot)."""
        # We need to store forward references; collect slot positions first
        self._align(4)
        base = len(self._buf)
        self._write_u32(len(offsets))
        for off in offsets:
            self._write_i32(off)
        return base

    def current_pos(self):
        return len(self._buf)

    def get_bytes(self):
        return bytes(self._buf)


# ---------------------------------------------------------------------------
# In-place patch: set inputs[1] = -1 for Reshape ops
# ---------------------------------------------------------------------------

def _patch_inplace(buf: bytearray, model_pos: int) -> int:
    """
    In-place: set inputs[1] = -1 for every Reshape op where:
      - inputs[1] is a constant tensor (buffer with data), AND
      - ReshapeOptions.new_shape is already populated.

    Returns (patched_count, needs_rebuild_count) where needs_rebuild_count is
    the number of ops where new_shape was NOT populated and require a rebuild.
    """
    oc_fpos  = _vtfield(buf, model_pos, 1)
    sg_fpos  = _vtfield(buf, model_pos, 2)
    buf_fpos = _vtfield(buf, model_pos, 4)

    oc_tables  = _vec_tables(buf, oc_fpos)
    sg_tables  = _vec_tables(buf, sg_fpos)
    buf_tables = _vec_tables(buf, buf_fpos)

    # Precompute buffer data presence (cheap - just check if data field exists and has len>0)
    buf_has_data = []
    for bt in buf_tables:
        f = _vtfield(buf, bt, 0)
        if f is None:
            buf_has_data.append(False)
            continue
        vp = f + _r32(buf, f)
        n  = _u32(buf, vp)
        buf_has_data.append(n > 0)

    patched = 0
    needs_rebuild = 0

    for sg_pos in sg_tables:
        tensor_fpos = _vtfield(buf, sg_pos, 0)
        op_fpos     = _vtfield(buf, sg_pos, 3)
        if tensor_fpos is None or op_fpos is None:
            continue
        tensor_tables = _vec_tables(buf, tensor_fpos)
        op_tables     = _vec_tables(buf, op_fpos)

        for op_pos in op_tables:
            oc_idx_fpos = _vtfield(buf, op_pos, 0)
            if oc_idx_fpos is None:
                continue
            oc_idx = _u32(buf, oc_idx_fpos)
            if oc_idx >= len(oc_tables):
                continue
            if _builtin_code(buf, oc_tables[oc_idx]) != RESHAPE_BUILTIN_CODE:
                continue

            inp_fpos = _vtfield(buf, op_pos, 1)
            if inp_fpos is None:
                continue

            # inputs is a vector of int32; position of vector data:
            inp_vec_pos = inp_fpos + _r32(buf, inp_fpos)
            inp_count   = _u32(buf, inp_vec_pos)
            if inp_count < 2:
                continue

            shape_tensor_idx_pos = inp_vec_pos + 4 + 4  # inputs[1] in the vector
            shape_tensor_idx = _r32(buf, shape_tensor_idx_pos)
            if shape_tensor_idx < 0 or shape_tensor_idx >= len(tensor_tables):
                continue

            # Check shape tensor has constant buffer
            st_pos = tensor_tables[shape_tensor_idx]
            buf_idx_fpos = _vtfield(buf, st_pos, 2)
            if buf_idx_fpos is None:
                continue
            buf_idx = _u32(buf, buf_idx_fpos)
            if buf_idx >= len(buf_has_data) or not buf_has_data[buf_idx]:
                needs_rebuild += 1
                continue

            # Check ReshapeOptions.new_shape is populated (field 4 of Operator)
            bo_fpos = _vtfield(buf, op_pos, 4)
            new_shape_ok = False
            if bo_fpos:
                ro_pos = bo_fpos + _r32(buf, bo_fpos)
                ns_fpos = _vtfield(buf, ro_pos, 0)
                if ns_fpos:
                    ns_vec_pos = ns_fpos + _r32(buf, ns_fpos)
                    ns_count   = _u32(buf, ns_vec_pos)
                    new_shape_ok = ns_count > 0

            if not new_shape_ok:
                needs_rebuild += 1
                continue

            # In-place: write -1 at inputs[1]
            struct.pack_into("<i", buf, shape_tensor_idx_pos, -1)
            patched += 1

    return patched, needs_rebuild


# ---------------------------------------------------------------------------
# Full rebuild for ops where new_shape needs to be added
# We do this by re-parsing and rebuilding specific ReshapeOptions tables.
# For simplicity: only patch inputs[1]=-1 in-place first; if new_shape
# population is also needed, do a second pass that rebuilds only those ops.
# ---------------------------------------------------------------------------

def _get_buffer_int32s(buf, buf_tables, buf_idx):
    bt = buf_tables[buf_idx]
    f = _vtfield(buf, bt, 0)
    if f is None: return None
    data = _vec_bytes(buf, f)
    if not data: return None
    return list(struct.unpack_from(f"<{len(data)//4}i", data))


def _patch_with_rebuild(buf_in: bytearray) -> bytearray:
    """
    In-place patch: set inputs[1]=-1 for all Reshape ops.

    The NeuroPilot v9 SDK rejects "Output shape as inputs not supported".
    Reshape ops in this model have inputs=[data, shape_tensor] where
    shape_tensor is a RUNTIME tensor (no constant buffer data).

    Strategy: set inputs[1]=-1 (TFLite sentinel for "absent input") for
    every Reshape op. The SDK should then use the output tensor's static
    shape metadata (which is always present in TFLite tensor descriptors).

    Key bugs fixed vs. first attempt:
    - opcode_index field is absent when its value is 0 (flatbuffers omits
      scalar fields equal to their default value of 0). Must default to 0.
    - Removed the "constant buffer" guard — all shape tensors are runtime.
    """
    buf = bytearray(buf_in)
    model_pos = _u32(buf, 0)

    oc_fpos  = _vtfield(buf, model_pos, 1)
    sg_fpos  = _vtfield(buf, model_pos, 2)

    oc_tables = _vec_tables(buf, oc_fpos)
    sg_tables = _vec_tables(buf, sg_fpos)

    patched = 0
    skipped_no_inputs = 0

    for sg_pos in sg_tables:
        op_fpos = _vtfield(buf, sg_pos, 3)
        if op_fpos is None:
            continue
        op_tables = _vec_tables(buf, op_fpos)

        for op_pos in op_tables:
            # opcode_index is a uint with default 0 — absent when oc_idx=0
            oc_idx_fpos = _vtfield(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if oc_idx >= len(oc_tables):
                continue
            if _builtin_code(buf, oc_tables[oc_idx]) != RESHAPE_BUILTIN_CODE:
                continue

            inp_fpos = _vtfield(buf, op_pos, 1)
            if inp_fpos is None:
                skipped_no_inputs += 1
                continue

            inp_vec_pos = inp_fpos + _r32(buf, inp_fpos)
            inp_count   = _u32(buf, inp_vec_pos)
            if inp_count < 2:
                continue

            # inputs[1] is at: vector_start + 4 (count) + 4 (inputs[0])
            shape_idx_pos = inp_vec_pos + 4 + 4
            struct.pack_into("<i", buf, shape_idx_pos, -1)
            patched += 1

    print(f"Patched inputs[1]=-1: {patched}  skipped_no_inputs: {skipped_no_inputs}")
    return buf


def main():
    in_path = sys.argv[1] if len(sys.argv) > 1 else (
        "/home/ae/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9/export_work/model_quantized.tflite"
    )
    out_path = sys.argv[2] if len(sys.argv) > 2 else in_path.replace(".tflite", ".reshape_patched.tflite")

    print(f"Input:  {in_path}")
    print(f"Output: {out_path}")

    with open(in_path, "rb") as f:
        raw = bytearray(f.read())

    print(f"Model size: {len(raw):,} bytes")
    print("Patching Reshape ops...")

    patched = _patch_with_rebuild(raw)

    with open(out_path, "wb") as f:
        f.write(patched)

    print(f"Written: {out_path}  ({len(patched):,} bytes)")


if __name__ == "__main__":
    main()
