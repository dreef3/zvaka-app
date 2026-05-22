#!/usr/bin/env python3
"""Binary-patch NeuronAdapter libneuron_adapter.so to skip FC bias floating-point type check.

v9_0_3 validates that FullyConnected bias tensors are floating-point types.
Quantized models have INT32 biases, which triggers:
  "Bias should be floating point type" → 0 ops selected → apply_plugin fails.

The check (unique 16-byte pattern in v9_0_3):
  0f 87 XX XX XX XX   JA  <bias_error>     # bias type > 0x2d
  48 0f a3 c2          BT  %rax,%rdx        # test bit in float-type bitmask
  0f 83 XX XX XX XX   JAE <bias_error>     # bit not set → not float

Patch: NOP both conditional jumps (6 bytes each → 6×0x90), leaving BT in place.
Execution falls through to the success path for both checks.

Usage: python3 patch_fc_bias_check.py <path_to_libneuron_adapter.so>
"""
import sys, pathlib, shutil

BETWEEN = bytes([0x48, 0x0f, 0xa3, 0xc2])  # BT %rax,%rdx (between the two jumps)
NOP6    = bytes([0x90] * 6)

if len(sys.argv) != 2:
    print(f"Usage: {sys.argv[0]} <path_to_libneuron_adapter.so>")
    sys.exit(1)

so_path = pathlib.Path(sys.argv[1])
if not so_path.exists():
    print(f"ERROR: {so_path} not found"); sys.exit(1)

data = bytearray(so_path.read_bytes())

# Find pattern: JA(6) + BT %rax,%rdx (4) + JAE(6)
occurrences = []
i = 0
while i < len(data) - 16:
    if (data[i] == 0x0f and data[i+1] == 0x87 and
            data[i+6:i+10] == BETWEEN and
            data[i+10] == 0x0f and data[i+11] == 0x83):
        occurrences.append(i)
        i += 16
    else:
        i += 1

if not occurrences:
    if data.find(NOP6 + BETWEEN + NOP6) != -1:
        print(f"Already patched — skipping: {so_path}")
        sys.exit(0)
    print(f"ERROR: FC bias check pattern not found in {so_path}")
    sys.exit(1)

bak = pathlib.Path(str(so_path) + ".bak_fcbias")
if not bak.exists():
    shutil.copy2(so_path, bak)
    print(f"Backup: {bak}")

for i in occurrences:
    data[i:i+6]     = NOP6  # NOP the JA
    data[i+10:i+16] = NOP6  # NOP the JAE
    print(f"Patched FC bias JA+JAE at file offset 0x{i:x}")

so_path.write_bytes(data)
print(f"Total patches: {len(occurrences)}")
