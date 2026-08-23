#!/bin/sh
set -e

# One-time host preparation for rootless podman lab.
# Usage: sh lab/setup_host.sh [username]

TARGET_USER="${1:-$(id -un)}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

echo "=== [NADAMU LAB: ROOTLESS PODMAN HOST SETUP] ==="
echo "[*] Target user: ${TARGET_USER}"

# 1. Required packages (Debian/Kali/Ubuntu hosts)
if command -v apt-get >/dev/null 2>&1; then
    echo "[*] Installing required packages..."
    sudo apt-get update
    # docker-compose-v2 provides 'docker compose' CLI usable over the podman socket
    sudo apt-get install -y podman uidmap slirp4netns passt fuse-overlayfs crun curl docker-compose-v2 \
        || sudo apt-get install -y podman uidmap slirp4netns fuse-overlayfs crun curl
else
    echo "[!] Non-apt host: install podman, uidmap, slirp4netns/passt, fuse-overlayfs, crun manually"
fi

# 2. Subordinate UID/GID ranges (mandatory for rootless operation)
if ! grep -q "^${TARGET_USER}:" /etc/subuid 2>/dev/null; then
    echo "[*] Adding subordinate UID/GID ranges..."
    sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 "${TARGET_USER}"
else
    echo "[+] Subordinate UID/GID ranges already present"
fi

# 3. KVM device access
if ! id -nG "${TARGET_USER}" | tr ' ' '\n' | grep -qx kvm; then
    echo "[*] Adding ${TARGET_USER} to kvm group..."
    sudo usermod -aG kvm "${TARGET_USER}"
    echo "[!] Group change requires re-login to take effect!"
else
    echo "[+] User already in kvm group"
fi

# 4. Unprivileged user namespaces (Debian-family kernels)
if [ -r /proc/sys/kernel/unprivileged_userns_clone ] && \
   [ "$(cat /proc/sys/kernel/unprivileged_userns_clone)" != "1" ]; then
    echo "[*] Enabling unprivileged user namespaces..."
    echo 'kernel.unprivileged_userns_clone=1' | sudo tee /etc/sysctl.d/99-rootless-podman.conf >/dev/null
    sudo sysctl --system
else
    echo "[+] User namespaces OK"
fi

# 5. Linger: keep user services alive after SSH logout (critical on servers)
if [ "$(loginctl show-user "${TARGET_USER}" --property=Linger --value 2>/dev/null)" != "yes" ]; then
    echo "[*] Enabling linger for ${TARGET_USER}..."
    sudo loginctl enable-linger "${TARGET_USER}"
else
    echo "[+] Linger already enabled"
fi

# 6. User-level systemd units
echo "[*] Enabling user-level podman units..."
systemctl --user enable --now podman.socket
systemctl --user enable --now podman-restart.service

# 7. Smoke tests
echo "[*] Running smoke tests..."
podman info >/dev/null && echo "[+] podman runtime OK"

SOCKET="${XDG_RUNTIME_DIR}/podman/podman.sock"
if curl -sf --unix-socket "${SOCKET}" http://localhost/_ping >/dev/null 2>&1; then
    echo "[+] Podman API socket responds at ${SOCKET}"
else
    echo "[!] Socket not responding: ${SOCKET}"
    echo "[!] Try: systemctl --user restart podman.socket"
fi

echo ""
echo "=== Setup complete ==="
echo "Next steps:"
echo "  1. Re-login (applies kvm group membership)"
echo "  2. cd lab && podman-compose up -d --build"
echo "  3. python3 lab/test_unlock.py"
