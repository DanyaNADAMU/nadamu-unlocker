#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== [NADAMU LAB: LOCAL TEST RUN] ==="

# Run the python test script
echo "[*] Executing test_unlock.py..."
python3 "${SCRIPT_DIR}/test_unlock.py"
