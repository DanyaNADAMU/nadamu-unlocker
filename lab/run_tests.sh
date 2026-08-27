#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== [NADAMU LAB: LOCAL TEST RUN] ==="

# Run the python test suite
echo "[*] Executing test_unlock.py suite..."
python3 "${SCRIPT_DIR}/test_unlock.py" "$@"
