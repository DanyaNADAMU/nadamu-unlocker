#!/bin/sh
# nadamu-unlocker installer for laptop (Kali / Debian)
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "[-] This script must be run as root (sudo ./install.sh)"
    exit 1
fi

echo "=== [nadamu-unlocker] Installing hooks for Kali/Debian ==="

# 1. Check required packages
REQUIRED_PKGS="dropbear-initramfs cryptsetup-initramfs busybox"
for pkg in $REQUIRED_PKGS; do
    if ! dpkg -s "$pkg" >/dev/null 2>&1; then
        echo "[*] Installing missing dependency: $pkg..."
        apt-get update -qq && apt-get install -y "$pkg"
    fi
done

# 2. Install unlock CLI tool to initramfs hook directory
mkdir -p /etc/initramfs-tools/hooks
mkdir -p /etc/initramfs-tools/scripts/init-premount
mkdir -p /etc/initramfs-tools/scripts/init-bottom

# Copy local unlock helper
cp bin/unlock /usr/local/bin/unlock
chmod 755 /usr/local/bin/unlock

# 3. Create hook to include unlock script and passfifo handling into initramfs
cat << 'EOF' > /etc/initramfs-tools/hooks/nadamu_unlock
#!/bin/sh
PREREQ=""
prereqs() { echo "$PREREQ"; }
case $1 in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

# Copy unlock binary
copy_exec /usr/local/bin/unlock /bin/unlock
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_unlock

# 4. Configure Dropbear initramfs options (disable password auth, keys only)
DROPBEAR_CONF="/etc/dropbear/initramfs/dropbear.conf"
if [ -f "$DROPBEAR_CONF" ]; then
    if ! grep -q 'DROPBEAR_OPTIONS="-s -j -k"' "$DROPBEAR_CONF"; then
        echo 'DROPBEAR_OPTIONS="-s -j -k -p 22"' >> "$DROPBEAR_CONF"
        echo "[+] Configured Dropbear for public-key authentication only."
    fi
fi

# 5. Rebuild initramfs
echo "[*] Updating initramfs image..."
update-initramfs -u -k all

echo "[+] nadamu-unlocker installation completed successfully!"
