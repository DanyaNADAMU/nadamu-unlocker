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
    elif getent hosts nadamu-unlocker-lab >/dev/null 2>&1; then
        HOST="nadamu-unlocker-lab"
        PORT="${PORT:-22}"
    else
        HOST="127.0.0.1"
        PORT="${PORT:-2222}"
    fi
fi
PORT="${PORT:-2222}"

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
