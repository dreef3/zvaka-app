#!/usr/bin/env bash
# patch_no_import_forever.sh — patch libLiteRtCompilerPlugin_MediaTek.so in
# the active venv to remove import_forever=true from the default APUSys config.
#
# Run this after `uv pip install ai-edge-litert` and before the export.
# The patch replaces the 46-byte string:
#   --apusys-config "{ \"import_forever\": true }"
# with:
#   --apusys-config "{}"\x00...  (null-padded to same length)
#
# This causes NeuronCompilation_createV2 to receive an empty JSON config,
# so the DLA is compiled WITHOUT import_forever baked in.

set -euo pipefail

VENV=${VENV:-${1:-}}

if [[ -z "$VENV" ]]; then
  # Try to detect venv from activate script
  VENV=$(python3 -c "import sys; print(sys.prefix)" 2>/dev/null || true)
fi

if [[ -z "$VENV" || ! -d "$VENV" ]]; then
  echo "ERROR: Could not determine venv. Pass VENV= or activate the venv first." >&2
  exit 1
fi

SO_PATH=$(find "$VENV" -name "libLiteRtCompilerPlugin_MediaTek.so" 2>/dev/null | head -1)
if [[ -z "$SO_PATH" ]]; then
  echo "ERROR: libLiteRtCompilerPlugin_MediaTek.so not found in $VENV" >&2
  exit 1
fi

echo "Patching: $SO_PATH"

python3 - "$SO_PATH" << 'PYEOF'
import sys, os

so_path = sys.argv[1]
with open(so_path, 'rb') as f:
    data = bytearray(f.read())

original = b'--apusys-config "{ \\"import_forever\\": true }"'
replacement_str = b'--apusys-config "{}"'
replacement = replacement_str + b'\x00' * (len(original) - len(replacement_str))

assert len(replacement) == len(original), \
    f"Length mismatch: {len(replacement)} != {len(original)}"

count = data.count(original)
if count == 0:
    # Check if already patched
    if replacement_str in data and original not in data:
        print("Already patched (import_forever not found, empty config present). No-op.")
        sys.exit(0)
    print(f"ERROR: import_forever string not found in {so_path}", file=sys.stderr)
    sys.exit(1)

backup_path = so_path + '.bak'
if not os.path.exists(backup_path):
    import shutil
    shutil.copy2(so_path, backup_path)
    print(f"Backup: {backup_path}")

pos = 0
patches = 0
while True:
    idx = data.find(original, pos)
    if idx == -1:
        break
    data[idx:idx+len(original)] = replacement
    patches += 1
    pos = idx + len(replacement)

with open(so_path, 'wb') as f:
    f.write(data)

print(f"Patched {patches} occurrence(s) of import_forever in {so_path}")
PYEOF

echo "Done. Verifying..."
strings "$SO_PATH" | grep -E "import_forever|apusys-config" || true
echo "Patch complete."
