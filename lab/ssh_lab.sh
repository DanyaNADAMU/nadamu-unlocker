#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_PATH="${SCRIPT_DIR}/data/keys/id_ed25519"

if [ ! -f "$KEY_PATH" ]; then
    echo "[-] Error: SSH key not found at $KEY_PATH"
    echo "[-] Make sure the lab container is running and has built the assets."
    exit 1
fi

echo "[*] Connecting to lab via SSH (bypassing agent and known_hosts)..."
exec ssh \
    -o IdentityAgent=none \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -o LogLevel=ERROR \
    -i "$KEY_PATH" \
    -p 2222 \
    root@127.0.0.1 "$@"
