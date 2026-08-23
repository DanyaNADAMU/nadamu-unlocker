#!/bin/sh
set -e

echo "=== [NADAMU LAB: LOCAL TEST RUN] ==="

# 1. Check container logs to ensure KVM is active and initramfs is built
echo "[*] Container logs (last 40 lines):"
docker logs nadamu-unlocker-lab --tail 40

echo ""
# 2. Run the python test script
echo "[*] Executing test_unlock.py..."
python3 "$(dirname "$0")/test_unlock.py"
