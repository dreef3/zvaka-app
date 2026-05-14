#!/usr/bin/env python3
"""
add_reshape_new_shape.py

Three-pass TFLite patcher for NeuronAdapter (MediaTek NNAPI) compatibility:

Pass 0 — scalar tensor fix:
  Patch all rank-0 tensors (shape=[]) to shape=[1] so that mul_op_legalization
  can determine broadcast shapes without inserting a 1-operand NNAPI RESHAPE.

Pass 1 — embed offset-based RESHAPE shape buffers:
  reshape_op_legalization reads new_shape from the inputs[1] tensor's constant
  buffer.  When the Buffer table uses offset-based storage (Buffer.offset +
  Buffer.size fields — absolute byte offsets into the .tflite file) instead of
  embedded storage (Buffer.data field), NeuronAdapter treats the operand as
  non-constant.  MapReshapeOps then rejects it with "Output shape as inputs not
  supported", or if inputs[1] is absent, NeuronModel_finish() fails with
  "RESHAPE: Invalid number of in operands. Got 1 of 2".

  Fix: for every RESHAPE inputs[1] buffer that is offset-based, read the raw
  bytes from the absolute file offset, write a NEW Buffer table with the data
  embedded in the Buffer.data field, and update the existing buffers-vector slot
  (in-place, in the original flatbuffer) to point forward to the new table.

  Original operator tables are untouched — inputs[1] still points to the same
  shape tensor index, which now has an embedded constant buffer.

Pass 2 — FC op bias fix:
  DLA module verification rejects FC ops with absent bias (inputs[2]=-1) as
  "Bias should be floating point type".  Fix: point inputs[2] at inputs[0]
  (the float32 data tensor) so the type check passes.  The FC op still falls
  back to CPU (wrong bias shape), but other ops proceed to NPU.

Pass 3 — INT32 RESHAPE fix:
  NeuronAdapter's GetSupportedOperations wrongly marks RESHAPE ops with INT32
  data inputs as "supported".  MDLA then fails to compile them with
  "Unsupported layer", causing a fatal NoExecPlan error that aborts the
  whole compilation.

  Fix: for each RESHAPE where inputs[0] is INT32, create a new Operator table
  (appended at end of file, all forward references) with:
    - 1 input only (drop the shape tensor)
    - builtin_options.new_shape = the known constant shape values

  NNAPI RESHAPE requires exactly 2 inputs, so NeuronAdapter returns false for
  GetSupportedOperations → op excluded from NPU partitions → runs on CPU.
  The TFLite CPU RESHAPE kernel uses builtin_options.new_shape when NumInputs==1.

  Flatbuffer construction: write the Operator table body first (with placeholder
  uoffsets), then the referenced objects (inputs vector, outputs vector,
  ReshapeOptions table, new_shape vector) at higher addresses, patch back the
  uoffsets (all positive = forward), then write the vtable after everything and
  store a negative soffset in the table header.

Flatbuffer sign rule: all uoffset_t (uint32) values must be positive.
New tables are appended at the END of the buffer (higher addresses), so every
forward reference from an original slot to a new table is positive. ✓

Usage: python3 add_reshape_new_shape.py [in.tflite [out.tflite]]
"""

import struct
import sys

RESHAPE_BC = 22   # BuiltinOperator.RESHAPE

# ---------------------------------------------------------------------------
# Low-level flatbuffer helpers
# ---------------------------------------------------------------------------
def _r32(b, o): return struct.unpack_from("<i", b, o)[0]
def _u32(b, o): return struct.unpack_from("<I", b, o)[0]
def _u64(b, o): return struct.unpack_from("<Q", b, o)[0]
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
    vp = fpos + _r32(b, fpos)
    n  = _u32(b, vp)
    return [_r32(b, vp + 4 + i*4) for i in range(n)]

def _bc(b, oc_pos):
    f3 = _vf(b, oc_pos, 3)
    if f3: return _r32(b, f3)
    f0 = _vf(b, oc_pos, 0)
    if f0: return _s8(b, f0)
    return 0

def _read_offset_buffer_data(buf, bt_pos):
    """
    Read raw bytes from a Buffer table that uses offset-based storage
    (Buffer.offset field 1 = absolute file byte offset, Buffer.size field 2).
    Returns bytes or None if the fields are absent / out-of-range.
    """
    off_fp = _vf(buf, bt_pos, 1)   # Buffer field 1 = offset (uint64)
    sz_fp  = _vf(buf, bt_pos, 2)   # Buffer field 2 = size   (uint64)
    if off_fp is None or sz_fp is None:
        return None
    offset = _u64(buf, off_fp)
    size   = _u64(buf, sz_fp)
    if size == 0 or offset + size > len(buf):
        return None
    return bytes(buf[int(offset) : int(offset + size)])


# ---------------------------------------------------------------------------
# Appender: write new flatbuffer objects at the end of a mutable bytearray
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

    def write_embedded_buffer(self, data_bytes: bytes) -> int:
        """
        Append a Buffer table with embedded data (Buffer.data = [ubyte] field).

        Layout (all forward references → all positive offsets):
          [vtable: vsz=6, obj_sz=8, field[0] at obj_off=4]
          [Buffer table: soffset(→vtable, positive), data_uoffset(placeholder)]
          [data vector: uint32 count, raw bytes, 4-byte align pad]

        Returns the absolute position of the Buffer table.
        """
        # vtable
        self._align(2)
        vt = self.pos()
        self._u16(6)   # vtable size: 4 header + 1 field * 2
        self._u16(8)   # object size: 4 (soffset) + 4 (data field)
        self._u16(4)   # field[0] (data [ubyte]) at obj_off=4

        # Buffer table
        self._align(4)
        bt = self.pos()
        self._i32(bt - vt)          # soffset → vtable (positive: vtable written first)

        data_slot = self.pos()       # obj_off=4: data uoffset placeholder
        self._u32(0)

        # data vector: uint32 length + raw bytes
        self._align(4)
        data_vec = self.pos()
        self._u32(len(data_bytes))
        self.buf += data_bytes
        # re-align
        r = len(self.buf) % 4
        if r:
            self.buf += b'\x00' * (4 - r)

        # Fix up forward reference
        self.put_u32_at(data_slot, data_vec - data_slot)

        return bt


# ---------------------------------------------------------------------------
# Main patching function
# ---------------------------------------------------------------------------
def patch_model(buf_in: bytearray, apply_pass3: bool = True) -> bytearray:
    buf = bytearray(buf_in)
    app = Appender(buf)

    model_pos = _u32(buf, 0)
    oc_fpos   = _vf(buf, model_pos, 1)
    sg_fpos   = _vf(buf, model_pos, 2)
    buf_fpos  = _vf(buf, model_pos, 4)   # Model field 4 = buffers

    oc_tables     = _vec_tables(buf, oc_fpos)
    sg_tables     = _vec_tables(buf, sg_fpos)
    buffer_tables = _vec_tables(buf, buf_fpos) if buf_fpos else []

    # Absolute position of the buffers vector — computed BEFORE any appends
    # so that slot addresses are stable.
    bufs_vp = (buf_fpos + _r32(buf, buf_fpos)) if buf_fpos else 0

    # ---------------------------------------------------------------------------
    # Pass 0: Patch all scalar tensors (shape=[]) to shape=[1].
    #
    # mul_op_legalization creates internal NEURON_RESHAPE ops for scalar MUL
    # inputs to enable broadcasting.  When it can't determine the target shape
    # (rank-0 tensor) it emits a 1-operand NNAPI RESHAPE → NeuronModel_finish()
    # fails with "RESHAPE: Invalid number of in operands. Got 1 of 2".
    # ---------------------------------------------------------------------------
    scalar_patch_vec_pos: int | None = None

    def _get_scalar_patch_vec() -> int:
        nonlocal scalar_patch_vec_pos, buf
        if scalar_patch_vec_pos is None:
            r = len(buf) % 4
            if r:
                buf += b'\x00' * (4 - r)
            scalar_patch_vec_pos = len(buf)
            buf += struct.pack("<I", 1)   # count=1
            buf += struct.pack("<i", 1)   # value=1  → shape=[1]
        return scalar_patch_vec_pos

    total_scalar = 0
    for sg_pos in sg_tables:
        t_fpos = _vf(buf, sg_pos, 0)
        if t_fpos is None:
            continue
        tensor_tables = _vec_tables(buf, t_fpos)
        for tt in tensor_tables:
            ts_fpos = _vf(buf, tt, 0)
            if ts_fpos is None:
                continue
            if _vec_i32(buf, ts_fpos) != []:
                continue
            vec_pos = _get_scalar_patch_vec()
            struct.pack_into("<i", buf, ts_fpos, vec_pos - ts_fpos)
            total_scalar += 1

    print(f"Scalar tensors patched to shape=[1]: {total_scalar}", file=sys.stderr)

    # ---------------------------------------------------------------------------
    # Pass 1: Embed offset-based RESHAPE inputs[1] buffers.
    #
    # For each RESHAPE op whose inputs[1] shape tensor has an offset-based buffer,
    # copy the raw bytes into a new embedded Buffer table and redirect the
    # buffers-vector slot to the new table.  Operator tables are NOT modified.
    # ---------------------------------------------------------------------------
    patched_buf_ids: set[int] = set()
    patched_tensor_ts: set[int] = set()   # absolute ts_fpos positions already redirected
    total_embedded = 0

    for sg_pos in sg_tables:
        t_fpos  = _vf(buf, sg_pos, 0)
        op_fpos = _vf(buf, sg_pos, 3)
        if op_fpos is None:
            continue
        tensor_tables = _vec_tables(buf, t_fpos) if t_fpos else []
        op_tables     = _vec_tables(buf, op_fpos)

        for op_pos in op_tables:
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == RESHAPE_BC):
                continue

            inp_fpos = _vf(buf, op_pos, 1)
            inputs = _vec_i32(buf, inp_fpos) if inp_fpos else []
            if len(inputs) < 2 or inputs[1] < 0:
                continue

            shape_tidx = inputs[1]
            if shape_tidx >= len(tensor_tables):
                continue

            tt = tensor_tables[shape_tidx]
            buf_id_fp = _vf(buf, tt, 2)
            if buf_id_fp is None:
                continue
            buf_id = _u32(buf, buf_id_fp)
            if buf_id == 0 or buf_id >= len(buffer_tables):
                continue

            bt_pos = buffer_tables[buf_id]

            # Already originally embedded — nothing to do.
            if _vf(buf, bt_pos, 0) is not None:
                patched_buf_ids.add(buf_id)
                continue

            data_bytes = _read_offset_buffer_data(buf, bt_pos)
            if data_bytes is None:
                # Buffer has size=0 (empty shape tensor — scalar reshape target).
                # Derive the target shape from the output tensor, which Pass 0
                # has already patched from [] to [1].
                out_fpos2 = _vf(buf, op_pos, 2)
                outputs2  = _vec_i32(buf, out_fpos2) if out_fpos2 else []
                out_shape2: list[int] = []
                if outputs2 and 0 <= outputs2[0] < len(tensor_tables):
                    out_ts2 = _vf(buf, tensor_tables[outputs2[0]], 0)
                    out_shape2 = _vec_i32(buf, out_ts2) if out_ts2 else []
                if not out_shape2:
                    print(f"  WARN: buf_id={buf_id} size=0 and no output shape, skipping",
                          file=sys.stderr)
                    continue
                data_bytes = struct.pack(f"<{len(out_shape2)}i", *out_shape2)

                # The shape tensor declared shape=[0] (zero-element).  Now that
                # we're embedding len(out_shape2) int32 elements, the tensor's
                # declared shape must match or NeuronModel_setOperandValue will
                # see "setting N bytes when needing 0".  Patch it to [N].
                ts_fpos = _vf(buf, tt, 0)
                if ts_fpos is not None and ts_fpos not in patched_tensor_ts:
                    patched_tensor_ts.add(ts_fpos)
                    r = len(buf) % 4
                    if r:
                        buf += b'\x00' * (4 - r)
                    new_sv = len(buf)
                    new_ts = [len(out_shape2)]   # e.g. [1]
                    buf += struct.pack("<I", len(new_ts))
                    for v in new_ts:
                        buf += struct.pack("<i", v)
                    struct.pack_into("<i", buf, ts_fpos, new_sv - ts_fpos)
                    print(f"  Patched shape tensor {shape_tidx} shape → {new_ts} "
                          f"(derived from output {out_shape2})", file=sys.stderr)

            # Embed the buffer only once (multiple subgraphs may share buf_id).
            if buf_id in patched_buf_ids:
                continue

            new_bt = app.write_embedded_buffer(data_bytes)

            # Update buffers-vector slot in-place.
            # slot is in the original (low-address) vector;
            # new_bt is appended at the end (high address) → positive offset. ✓
            slot_pos = bufs_vp + 4 + buf_id * 4
            struct.pack_into("<i", buf, slot_pos, new_bt - slot_pos)

            patched_buf_ids.add(buf_id)
            total_embedded += 1

            shape_vals = [struct.unpack_from("<i", data_bytes, i*4)[0]
                          for i in range(len(data_bytes) // 4)]
            print(f"  Embedded buf_id={buf_id}: shape={shape_vals}", file=sys.stderr)

    print(f"RESHAPE inputs[1] buffers embedded: {total_embedded}", file=sys.stderr)

    # ---------------------------------------------------------------------------
    # Pass 2: Patch FC ops with absent bias (inputs[2]=-1) to reference the
    # data input tensor as a "fake" float32 bias.
    #
    # DLA module verification checks that FC bias is float32 (not that its shape
    # is correct).  Absent bias (-1) triggers "Bias should be floating point type"
    # → "Fail to verify the given module" → 0 ops selected for NPU.
    #
    # By pointing inputs[2] at the existing float32 data tensor (inputs[0]), the
    # type check passes.  NeuronAdapter will then reject the FC op at partition-
    # compile time (wrong bias shape or unsupported weight type → CPU fallback),
    # but non-FC ops in other partitions still get compiled for NPU.
    # ---------------------------------------------------------------------------
    FC_BC = 9
    total_fc_patched = 0

    for sg_pos in sg_tables:
        t_fpos  = _vf(buf, sg_pos, 0)
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
            inp_count = _u32(buf, inp_vec_pos)

            if inp_count < 3:
                continue   # no bias slot at all
            bias_slot = inp_vec_pos + 4 + 2 * 4   # element [2] of the vector
            if _r32(buf, bias_slot) != -1:
                continue   # bias already present

            # inputs[0] is the float32 data tensor — reuse its index as bias.
            data_idx = _r32(buf, inp_vec_pos + 4)
            struct.pack_into("<i", buf, bias_slot, data_idx)
            total_fc_patched += 1

    print(f"FC ops bias-patched (inputs[2] = inputs[0]): {total_fc_patched}",
          file=sys.stderr)

    # ---------------------------------------------------------------------------
    # Pass 3: Convert INT32 RESHAPE ops (2-input) → 1-input + builtin_options.new_shape.
    #
    # NeuronAdapter's GetSupportedOperations wrongly marks RESHAPE ops whose data
    # input (inputs[0]) is INT32 as "supported".  At partition-compile time MDLA
    # rejects them with "Unsupported layer", which returns error 6 (NoExecPlan) —
    # a FATAL error that aborts the whole AOT compilation.
    #
    # NNAPI mandates exactly 2 inputs for RESHAPE.  A 1-input RESHAPE makes
    # GetSupportedOperations return false → excluded from NPU partitions → CPU.
    # The TFLite CPU kernel uses builtin_options.new_shape when NumInputs == 1.
    #
    # Flatbuffer trick: write the new Operator TABLE first (placeholders), then
    # the referenced objects (inputs/outputs vectors, ReshapeOptions, new_shape
    # vector), patch the uoffsets back.  All references from the table to objects
    # are FORWARD (objects at higher addresses) → valid positive uoffset_t.
    # The vtable is written last; its soffset in the table is negative (vtable
    # after table → fine for int32 soffset_t).
    # ---------------------------------------------------------------------------
    INT32_TTYPE = 2    # TensorType.INT32
    BO_RESHAPE  = 17   # BuiltinOptions.ReshapeOptions union type
    total_int32_reshape = 0

    if not apply_pass3:
        print("Pass 3 skipped (--no-pass3)", file=sys.stderr)
        return buf

    # Re-read buffer_tables: Pass 1 patched the buffers-vector slots in buf,
    # but the Python list was built before those patches. Re-reading gives the
    # correct (post-embed) table positions so the data field lookup below works.
    if buf_fpos:
        buffer_tables = _vec_tables(buf, buf_fpos)

    for sg_idx, sg_pos in enumerate(sg_tables[:2]):  # only sg=0, sg=1 are NPU-compiled  # noqa: B007
        t_fpos  = _vf(buf, sg_pos, 0)
        op_fpos = _vf(buf, sg_pos, 3)
        if op_fpos is None:
            continue
        tensor_tables = _vec_tables(buf, t_fpos) if t_fpos else []
        op_tables     = _vec_tables(buf, op_fpos)
        ops_vp = op_fpos + _r32(buf, op_fpos)  # ops vector start

        for op_idx, op_pos in enumerate(op_tables):
            oc_idx_fpos = _vf(buf, op_pos, 0)
            oc_idx = 0 if oc_idx_fpos is None else _u32(buf, oc_idx_fpos)
            if not (oc_idx < len(oc_tables) and _bc(buf, oc_tables[oc_idx]) == RESHAPE_BC):
                continue

            inp_fpos = _vf(buf, op_pos, 1)
            inputs = _vec_i32(buf, inp_fpos) if inp_fpos else []
            if not inputs:
                continue
            data_tidx = inputs[0]
            if data_tidx < 0 or data_tidx >= len(tensor_tables):
                continue

            # Only target RESHAPE ops whose data input is INT32.
            tt_data = tensor_tables[data_tidx]
            type_fp = _vf(buf, tt_data, 1)
            if type_fp is None:
                continue  # absent = default = FLOAT32, skip
            ttype = struct.unpack_from("<b", buf, type_fp)[0]
            if ttype != INT32_TTYPE:
                continue

            # Read the known shape from inputs[1]'s buffer.
            if len(inputs) < 2 or inputs[1] < 0 or inputs[1] >= len(tensor_tables):
                continue
            shape_tidx = inputs[1]
            tt_shape = tensor_tables[shape_tidx]
            buf_id_fp = _vf(buf, tt_shape, 2)
            buf_id = _u32(buf, buf_id_fp) if buf_id_fp else 0
            new_shape: list[int] | None = None
            if buf_id > 0 and buf_id < len(buffer_tables):
                bt_pos = buffer_tables[buf_id]
                f0 = _vf(buf, bt_pos, 0)
                if f0 is not None:
                    data_vp2 = f0 + _r32(buf, f0)
                    data_len2 = _u32(buf, data_vp2)
                    if data_len2 > 0:
                        n2 = data_len2 // 4
                        new_shape = list(struct.unpack_from(f"<{n2}i", buf, data_vp2 + 4))
            # Fallback: read target shape from the output tensor (always static).
            if new_shape is None:
                out_fpos_p3 = _vf(buf, op_pos, 2)
                outputs_p3  = _vec_i32(buf, out_fpos_p3) if out_fpos_p3 else []
                if outputs_p3 and 0 <= outputs_p3[0] < len(tensor_tables):
                    out_ts_p3 = _vf(buf, tensor_tables[outputs_p3[0]], 0)
                    if out_ts_p3 is not None:
                        new_shape = _vec_i32(buf, out_ts_p3) or None
            if new_shape is None:
                print(f"  WARN: sg={sg_idx} op={op_idx}: INT32 RESHAPE shape unknown, skip",
                      file=sys.stderr)
                continue

            out_fpos = _vf(buf, op_pos, 2)
            outputs = _vec_i32(buf, out_fpos) if out_fpos else []
            if not outputs:
                continue

            print(f"  INT32 RESHAPE sg={sg_idx} op={op_idx}: "
                  f"data_tidx={data_tidx} new_shape={new_shape}", file=sys.stderr)

            # ------------------------------------------------------------------
            # Build the new Operator table at the end of the buffer.
            # Object layout (24 bytes):
            #   off  0: soffset_t      (4 B, placeholder → vtable, negative)
            #   off  4: opcode_index   (4 B, field 0, uint32 inline)
            #   off  8: inputs uoffset (4 B, field 1, placeholder → V_in)
            #   off 12: outputs uoffset(4 B, field 2, placeholder → V_out)
            #   off 16: bo_type        (1 B, field 3, byte = BO_RESHAPE=17)
            #   off 17: padding        (3 B)
            #   off 20: bo uoffset     (4 B, field 4, placeholder → T_ro)
            # ------------------------------------------------------------------
            while len(buf) % 4:
                buf += b'\x00'
            T_op = len(buf)
            buf += struct.pack("<i", 0)            # soffset placeholder
            buf += struct.pack("<I", oc_idx)       # opcode_index (field 0)
            buf += struct.pack("<I", 0)            # inputs uoffset placeholder (field 1)
            buf += struct.pack("<I", 0)            # outputs uoffset placeholder (field 2)
            buf += struct.pack("<B", BO_RESHAPE)   # builtin_options_type=17 (field 3)
            buf += b'\x00' * 3                     # padding
            buf += struct.pack("<I", 0)            # builtin_options uoffset placeholder (field 4)
            SOFFSET_OFF = 0; OC_OFF = 4; IN_OFF = 8; OUT_OFF = 12
            BOT_OFF = 16; BO_OFF = 20; OBJ_SZ = 24

            # inputs vector [data_tidx]
            while len(buf) % 4:
                buf += b'\x00'
            V_in = len(buf)
            buf += struct.pack("<I", 1) + struct.pack("<i", data_tidx)
            struct.pack_into("<I", buf, T_op + IN_OFF, V_in - (T_op + IN_OFF))

            # outputs vector
            while len(buf) % 4:
                buf += b'\x00'
            V_out = len(buf)
            buf += struct.pack("<I", len(outputs))
            for t in outputs:
                buf += struct.pack("<i", t)
            struct.pack_into("<I", buf, T_op + OUT_OFF, V_out - (T_op + OUT_OFF))

            # ReshapeOptions table (field 0 = new_shape uoffset, placeholder)
            while len(buf) % 4:
                buf += b'\x00'
            T_ro = len(buf)
            buf += struct.pack("<i", 0)   # ReshapeOptions soffset placeholder
            buf += struct.pack("<I", 0)   # new_shape uoffset placeholder

            # new_shape vector
            while len(buf) % 4:
                buf += b'\x00'
            V_ns = len(buf)
            buf += struct.pack("<I", len(new_shape))
            for d in new_shape:
                buf += struct.pack("<i", d)
            struct.pack_into("<I", buf, T_ro + 4, V_ns - (T_ro + 4))

            # ReshapeOptions vtable  [vsz=6, obj_sz=8, field0_off=4]
            while len(buf) % 2:
                buf += b'\x00'
            VT_ro = len(buf)
            buf += struct.pack("<HHH", 6, 8, 4)
            struct.pack_into("<i", buf, T_ro, T_ro - VT_ro)   # negative soffset

            # patch builtin_options uoffset in Operator table
            struct.pack_into("<I", buf, T_op + BO_OFF, T_ro - (T_op + BO_OFF))

            # Operator vtable  [vsz=14, obj_sz=24, f0=4, f1=8, f2=12, f3=16, f4=20]
            while len(buf) % 2:
                buf += b'\x00'
            VT_op = len(buf)
            buf += struct.pack("<HH", 14, OBJ_SZ)
            buf += struct.pack("<HHHHH", OC_OFF, IN_OFF, OUT_OFF, BOT_OFF, BO_OFF)
            struct.pack_into("<i", buf, T_op, T_op - VT_op)   # negative soffset

            # Redirect the ops-vector slot to the new Operator table.
            slot_pos = ops_vp + 4 + op_idx * 4
            struct.pack_into("<i", buf, slot_pos, T_op - slot_pos)

            total_int32_reshape += 1

    print(f"INT32 RESHAPE ops converted to 1-input+new_shape: {total_int32_reshape}",
          file=sys.stderr)
    return buf


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("input",  nargs="?", default=(
        "/home/ae/src/litert-build/tmp/gemma3-270m-mt6985-p128-c128-v9/export_work/model_quantized.tflite"
    ))
    ap.add_argument("output", nargs="?")
    ap.add_argument("--no-pass3", dest="no_pass3", action="store_true",
                    help="Skip Pass 3 (INT32 RESHAPE → 1-input). Use with LD_PRELOAD interposer.")
    args = ap.parse_args()

    in_path  = args.input
    out_path = args.output or in_path.replace(".tflite", ".ns_patched.tflite")

    print(f"Input:  {in_path}")
    print(f"Output: {out_path}")

    with open(in_path, "rb") as f:
        raw = bytearray(f.read())

    print(f"Size: {len(raw):,} bytes")
    patched = patch_model(raw, apply_pass3=not args.no_pass3)
    print(f"Patched size: {len(patched):,} bytes  (+{len(patched)-len(raw):,})")

    with open(out_path, "wb") as f:
        f.write(patched)

    print(f"Written: {out_path}")


if __name__ == "__main__":
    main()
