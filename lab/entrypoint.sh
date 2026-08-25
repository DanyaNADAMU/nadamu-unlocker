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
if [ ! -f "${DATA_DIR}/test_disk.img" ] || [ ! -f "${DATA_DIR}/test_initrd.img" ] || [ -f "${DATA_DIR}/cmd.rebuild" ]; then
    echo "[*] Building lab assets..."
    rm -f "${DATA_DIR}/cmd.rebuild"
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
    # setsid gives the script its own process group so we can kill QEMU AND
    # its pipeline children (tee) with one signal. Killing just $! orphans
    # qemu (it keeps :22 bound -> next run dies with EADDRINUSE).
    setsid sh "${RUN_SCRIPT}" &
    QEMU_PID=$!
    
    # Monitor QEMU process and restart triggers
    while kill -0 $QEMU_PID 2>/dev/null; do
        if [ -f "${DATA_DIR}/cmd.rebuild" ] || [ -f "${DATA_DIR}/cmd.restart" ]; then
            echo "[*] Restart/Rebuild trigger detected. Stopping QEMU PID $QEMU_PID..."
            rm -f "${DATA_DIR}/cmd.restart"
            kill -- -"$QEMU_PID" 2>/dev/null || true
            pkill -f qemu-system-x86_64 2>/dev/null || true
            break
        fi
        sleep 1
    done
    
    wait $QEMU_PID 2>/dev/null || true
    
    echo "[*] QEMU instance finished. Restarting in 1 second..."
    sleep 1
done
