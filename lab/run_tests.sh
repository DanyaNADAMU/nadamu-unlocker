#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== [NADAMU LAB: LOCAL TEST RUN] ==="

# Trigger VM restart so test runs against a clean fresh boot
touch "${SCRIPT_DIR}/data/cmd.restart" 2>/dev/null || true

# 1. Check container logs
echo "[*] Container logs (last 20 lines):"
docker logs nadamu-unlocker-lab --tail 20 2>/dev/null || podman logs nadamu-unlocker-lab --tail 20 2>/dev/null || true

echo ""
# 2. Run the python test script
echo "[*] Executing test_unlock.py..."
python3 "${SCRIPT_DIR}/test_unlock.py"
