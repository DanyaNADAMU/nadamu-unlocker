#!/bin/sh
set -e

echo "=== [NADAMU LAB INITIALIZATION] ==="

# Build disk and initrd if not existing
if [ ! -f /lab/test_disk.img ] || [ ! -f /lab/test_initrd.img ]; then
    echo "[*] Building lab assets..."
    /lab/build_lab.sh "${LUKS_PASSWORD:-nadamu-test-pass-1234}"
fi

echo "[*] Starting QEMU instance..."
exec /lab/run_qemu.sh
