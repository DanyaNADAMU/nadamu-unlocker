#!/bin/sh
set -e

DATA_DIR="/lab/data"
SCRIPTS_DIR="/lab/scripts"

# Use scripts from /lab/scripts if mounted from host, otherwise fallback to /lab
BUILD_SCRIPT="/lab/build_lab.sh"
RUN_SCRIPT="/lab/run_qemu.sh"
[ -f "${SCRIPTS_DIR}/build_lab.sh" ] && BUILD_SCRIPT="${SCRIPTS_DIR}/build_lab.sh"
[ -f "${SCRIPTS_DIR}/run_qemu.sh" ] && RUN_SCRIPT="${SCRIPTS_DIR}/run_qemu.sh"

echo "=== [NADAMU LAB INITIALIZATION] ==="

# Build disk and initrd if not existing
if [ ! -f "${DATA_DIR}/test_disk.img" ] || [ ! -f "${DATA_DIR}/test_initrd.img" ]; then
    echo "[*] Building lab assets..."
    sh "${BUILD_SCRIPT}"
fi

# Supervisor loop for continuous test execution
LOOP_COUNT=0
while true; do
    LOOP_COUNT=$((LOOP_COUNT + 1))
    
    # Check for rebuild / restart trigger
    if [ -f "${DATA_DIR}/cmd.rebuild" ]; then
        echo "[*] Rebuild trigger detected. Rebuilding test initrd..."
        rm -f "${DATA_DIR}/cmd.rebuild"
        sh "${BUILD_SCRIPT}" || true
    fi
    
    echo "[*] Launching QEMU instance (Run #${LOOP_COUNT})..."
    sh "${RUN_SCRIPT}" || true
    
    echo "[*] QEMU instance finished. Restarting in 2 seconds..."
    sleep 2
done
