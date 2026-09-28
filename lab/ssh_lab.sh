#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_PATH="${UNLOCKER_KEY_PATH:-${NADAMU_KEY_PATH:-${SCRIPT_DIR}/data/keys/id_ed25519}}"
HOST="${UNLOCKER_SSH_HOST:-${NADAMU_SSH_HOST}}"
PORT="${UNLOCKER_SSH_PORT:-${NADAMU_SSH_PORT}}"

if [ -z "$HOST" ]; then
    if getent hosts unlocker-lab >/dev/null 2>&1; then
        HOST="unlocker-lab"
        PORT="${PORT:-22}"
    elif python3 -c "import socket; s = socket.socket(); s.settimeout(0.3); s.connect(('127.0.0.1', 2222)); s.close()" 2>/dev/null; then
        HOST="127.0.0.1"
        PORT="${PORT:-2222}"
    else
        # Check default gateway (e.g. agent container communicating with host port forward)
        GW=$(awk '$2 == "00000000" { printf "%d.%d.%d.%d\n", "0x" substr($3, 7, 2), "0x" substr($3, 5, 2), "0x" substr($3, 3, 2), "0x" substr($3, 1, 2) }' /proc/net/route 2>/dev/null | head -n 1)
        if [ -n "$GW" ] && python3 -c "import socket; s = socket.socket(); s.settimeout(0.3); s.connect(('$GW', 2222)); s.close()" 2>/dev/null; then
            HOST="$GW"
            PORT="${PORT:-2222}"
        else
            HOST="127.0.0.1"
            PORT="${PORT:-2222}"
        fi
    fi
else
    PORT="${PORT:-2222}"
fi

if [ ! -f "$KEY_PATH" ]; then
    echo "[-] Error: SSH key not found at $KEY_PATH"
    echo "[-] Make sure the lab container is running and has built the assets."
    exit 1
fi

echo "[*] Connecting to lab at ${HOST}:${PORT} via SSH (bypassing agent and known_hosts)..."
exec ssh \
    -F /dev/null \
    -o IdentityAgent=none \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -o LogLevel=ERROR \
    -o PasswordAuthentication=no \
    -o KbdInteractiveAuthentication=no \
    -o PubkeyAuthentication=yes \
    -i "$KEY_PATH" \
    -p "$PORT" \
    "root@${HOST}" "$@"
