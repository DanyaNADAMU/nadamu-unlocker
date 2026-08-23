#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== [NADAMU LAB: LOCAL TEST RUN] ==="

# Restart container to ensure a fresh, unhalted VM instance is running
podman restart nadamu-unlocker-lab >/dev/null 2>&1 || docker restart nadamu-unlocker-lab >/dev/null 2>&1 || true

# 1. Run the python test script
echo "[*] Executing test_unlock.py..."
python3 "${SCRIPT_DIR}/test_unlock.py"
