#!/usr/bin/env python3
"""Binary-patch NeuronAdapter libneuron_adapter.so to skip MapReshapeOps input-count check.

The check (identical pattern in v8_0_10 and v9_0_3):
  add $0xfffffffd,%ecx   # ecx -= 3
  cmp $0x2,%ecx          # 83 f9 02
  jae <error>            # 73 2a  → "MapReshapeOps: Output shape as inputs not supported"

Patch: change JAE → NOP NOP (90 90) so execution always falls through to the success path,
allowing RESHAPE ops with a shape tensor (input 1) to be compiled without error.

Known offsets:
  v8_0_10: 0xc21f40  (18 MB SO)
  v9_0_3:  0x1166170 (25 MB SO)

The script searches the whole file for the unique 5-byte pattern, so it works for both.

Usage: python3 patch_v8_mapresha.py <path_to_libneuron_adapter.so>
"""
import sys, pathlib, shutil

EXPECTED_SEQ = bytes([0x83, 0xf9, 0x02, 0x73, 0x2a])  # CMP + JAE
PATCH_SEQ    = bytes([0x83, 0xf9, 0x02, 0x90, 0x90])   # CMP + NOP NOP

if len(sys.argv) != 2:
    print(f"Usage: {sys.argv[0]} <path_to_libneuron_adapter.so>")
    sys.exit(1)

so_path = pathlib.Path(sys.argv[1])
if not so_path.exists():
    print(f"ERROR: {so_path} not found"); sys.exit(1)

data = bytearray(so_path.read_bytes())

# Find all occurrences of the pattern
occurrences = []
search_from = 0
while True:
    idx = data.find(EXPECTED_SEQ, search_from)
    if idx == -1:
        break
    occurrences.append(idx)
    search_from = idx + 1

if not occurrences:
    # Check if already patched
    patched_seq = bytes([0x83, 0xf9, 0x02, 0x90, 0x90])
    if data.find(patched_seq) != -1:
        print(f"Already patched — skipping: {so_path}")
        sys.exit(0)
    print(f"ERROR: pattern {EXPECTED_SEQ.hex()} not found in {so_path}")
    sys.exit(1)

bak = pathlib.Path(str(so_path) + ".bak")
if not bak.exists():
    shutil.copy2(so_path, bak)
    print(f"Backup: {bak}")

for idx in occurrences:
    data[idx:idx+5] = PATCH_SEQ
    print(f"Patched MapReshapeOps JAE → NOP at 0x{idx:x} in {so_path}")

so_path.write_bytes(data)
print(f"Total patches applied: {len(occurrences)}")
