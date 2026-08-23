#!/bin/sh
set -e

DATA_DIR="/lab/data"
KEYS_DIR="${DATA_DIR}/keys"
LUKS_PASSWORD="${LUKS_PASSWORD:-password}"

mkdir -p "${DATA_DIR}" "${KEYS_DIR}"

echo "=== [NADAMU LAB INITIALIZATION (KALI STANDARD UPDATE-INITRAMFS)] ==="

# 1. Generate BOTH Ed25519 and RSA keys to ensure compatibility
echo "[*] 1. Generating fresh test SSH keys (Ed25519 and RSA)..."
rm -f "${KEYS_DIR}/id_ed25519"* "${KEYS_DIR}/id_rsa"*
ssh-keygen -t ed25519 -N "" -f "${KEYS_DIR}/id_ed25519" -C "nadamu-test-ed25519"
ssh-keygen -t rsa -b 2048 -N "" -f "${KEYS_DIR}/id_rsa" -C "nadamu-test-rsa"

PUBKEY_ED25519=$(cat "${KEYS_DIR}/id_ed25519.pub")
PUBKEY_RSA=$(cat "${KEYS_DIR}/id_rsa.pub")

# 2. Create raw test disk image (500MB sparse) if missing
if [ ! -f "${DATA_DIR}/test_disk.img" ]; then
    echo "[*] 2. Creating raw disk image (500MB sparse)..."
    truncate -s 500M "${DATA_DIR}/test_disk.img"

    echo "[*] 3. Formatting disk with LUKS2 (cryptsetup)..."
    printf "%s" "${LUKS_PASSWORD}" | cryptsetup luksFormat --type luks2 --pbkdf pbkdf2 --batch-mode "${DATA_DIR}/test_disk.img" -
fi

# 3. Detect installed kernel version
KERNEL_VER=$(ls -1 /lib/modules 2>/dev/null | tail -n 1)
if [ -z "${KERNEL_VER}" ]; then
    echo "[-] Error: No kernel modules found in /lib/modules"
    exit 1
fi
echo "[*] 4. Detected kernel version: ${KERNEL_VER}"

# Copy vmlinuz to data
cp -f "/boot/vmlinuz-${KERNEL_VER}" "${DATA_DIR}/vmlinuz"

# 4. Configure standard Kali initramfs-tools & dropbear-initramfs
echo "[*] 5. Configuring official dropbear-initramfs and hooks..."

# Enable dropbear in initramfs explicitly
echo "DROPBEAR=y" >> /etc/initramfs-tools/initramfs.conf

if [ ! -f /usr/share/initramfs-tools/hooks/dropbear ]; then
    echo "[-] ERROR: dropbear hook not found at /usr/share/initramfs-tools/hooks/dropbear"
    exit 1
fi
chmod +x /usr/share/initramfs-tools/hooks/dropbear

# Generate dropbear host keys
mkdir -p /etc/dropbear/initramfs
for kt in rsa ecdsa ed25519; do
    if [ ! -f "/etc/dropbear/initramfs/dropbear_${kt}_host_key" ]; then
        dropbearkey -t ${kt} -f "/etc/dropbear/initramfs/dropbear_${kt}_host_key" 2>/dev/null || true
    fi
done

# Write config to both legacy and new Debian paths
mkdir -p /etc/dropbear-initramfs /root/.ssh
echo 'DROPBEAR_OPTIONS="-p 22 -s -j -k -E"' > /etc/dropbear/initramfs/dropbear.conf
echo 'DROPBEAR_OPTIONS="-p 22 -s -j -k -E"' > /etc/dropbear-initramfs/config

# Add both keys to authorized_keys
{
    echo "${PUBKEY_ED25519}"
    echo "${PUBKEY_RSA}"
} > /etc/dropbear/initramfs/authorized_keys

cp -f /etc/dropbear/initramfs/authorized_keys /etc/dropbear-initramfs/authorized_keys
cp -f /etc/dropbear/initramfs/authorized_keys /root/.ssh/authorized_keys
chmod 600 /etc/dropbear/initramfs/authorized_keys /etc/dropbear-initramfs/authorized_keys /root/.ssh/authorized_keys

# Make sure MODULES=most so QEMU virtio/net/blk drivers are included
sed -i 's/^MODULES=.*/MODULES=most/' /etc/initramfs-tools/initramfs.conf 2>/dev/null || true

cat << 'EOF' > /etc/initramfs-tools/modules
virtio_pci
virtio_net
virtio_blk
dm_mod
dm_crypt
aes
xts
sha256
sha512
EOF

# Hook to copy /bin/unlock helper, setup passfifo, and FIX PERMISSIONS
cat << 'EOF' > /etc/initramfs-tools/hooks/nadamu_unlock
#!/bin/sh
set -e

PREREQ="dropbear"
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

mkdir -p "${DESTDIR}/lib/cryptsetup" "${DESTDIR}/root/.ssh"
[ -p "${DESTDIR}/lib/cryptsetup/passfifo" ] || mkfifo "${DESTDIR}/lib/cryptsetup/passfifo"
chmod 600 "${DESTDIR}/lib/cryptsetup/passfifo"

# Force copy authorized_keys
if [ -f /etc/dropbear/initramfs/authorized_keys ]; then
    cp -f /etc/dropbear/initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
fi

# CRITICAL: Fix ownership and permissions for Dropbear
chown -R 0:0 "${DESTDIR}/root" 2>/dev/null || true
chmod 0700 "${DESTDIR}/root" "${DESTDIR}/root/.ssh" 2>/dev/null || true
chmod 0600 "${DESTDIR}/root/.ssh/authorized_keys" 2>/dev/null || true

# Unlock root account in shadow if it exists (Dropbear might reject locked accounts)
if [ -f "${DESTDIR}/etc/shadow" ]; then
    sed -i 's/^root:[^:]*:/root::/' "${DESTDIR}/etc/shadow"
fi

if [ -f /lab/laptop/bin/unlock ]; then
    copy_exec /lab/laptop/bin/unlock /bin/unlock
fi
copy_exec /sbin/cryptsetup /sbin/cryptsetup
exit 0
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_unlock

# Script to unlock LUKS via passfifo during local-top
cat << 'EOF' > /etc/initramfs-tools/scripts/local-top/nadamu_cryptroot
#!/bin/sh
PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

mkdir -p /lib/cryptsetup
[ -p /lib/cryptsetup/passfifo ] || mkfifo /lib/cryptsetup/passfifo

echo "=========================================="
echo "    NADAMU LUKS UNLOCK READY"
echo "=========================================="
echo "[*] Debug: Checking Dropbear authorized_keys in initramfs:"
ls -ld /root || echo "No /root"
ls -ld /root/.ssh || echo "No /root/.ssh"
ls -l /root/.ssh/authorized_keys || echo "No authorized_keys"

echo "[*] Waiting for passphrase on /lib/cryptsetup/passfifo..."

while [ ! -b /dev/mapper/test_crypt ]; do
    if [ -p /lib/cryptsetup/passfifo ]; then
        PASS=$(cat /lib/cryptsetup/passfifo 2>/dev/null)
        if [ -n "$PASS" ]; then
            echo "[*] Received passphrase, attempting cryptsetup open..."
            printf "%s" "$PASS" | cryptsetup open --type luks /dev/vda test_crypt -
            if [ -b /dev/mapper/test_crypt ]; then
                echo "[+] LUKS device test_crypt opened successfully!"
                if ! blkid /dev/mapper/test_crypt | grep -q ext4; then
                    mke2fs -t ext4 -F /dev/mapper/test_crypt >/dev/null 2>&1
                fi
                break
            else
                echo "[-] Invalid passphrase, waiting again..."
            fi
        fi
    fi
    sleep 1
done
exit 0
EOF
chmod +x /etc/initramfs-tools/scripts/local-top/nadamu_cryptroot

echo "[*] 6. Building official Kali initramfs image via update-initramfs..."
update-initramfs -v -u -k "${KERNEL_VER}" >/tmp/initramfs-build.log 2>&1 || update-initramfs -v -c -k "${KERNEL_VER}" >/tmp/initramfs-build.log 2>&1

cp -f "/boot/initrd.img-${KERNEL_VER}" "${DATA_DIR}/test_initrd.img"
echo "[*] 8. Lab assets built successfully in ${DATA_DIR}"
