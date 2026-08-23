#!/bin/sh
set -e

DATA_DIR="/lab/data"
KEYS_DIR="${DATA_DIR}/keys"
LUKS_PASSWORD="${LUKS_PASSWORD:-password}"

mkdir -p "${DATA_DIR}" "${KEYS_DIR}"

echo "=== [NADAMU LAB INITIALIZATION (KALI STANDARD UPDATE-INITRAMFS)] ==="

# 1. Generate SSH key pair for test client if missing
if [ ! -f "${KEYS_DIR}/id_ed25519" ]; then
    echo "[*] 1. Generating test SSH keys (ed25519)..."
    ssh-keygen -t ed25519 -N "" -f "${KEYS_DIR}/id_ed25519" -C "nadamu-test-client"
fi

PUBKEY_CONTENT=$(cat "${KEYS_DIR}/id_ed25519.pub")

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

# Generate dropbear host keys to prevent connection reset during KEX
mkdir -p /etc/dropbear/initramfs
for kt in rsa ecdsa ed25519; do
    if [ ! -f "/etc/dropbear/initramfs/dropbear_${kt}_host_key" ]; then
        dropbearkey -t ${kt} -f "/etc/dropbear/initramfs/dropbear_${kt}_host_key" 2>/dev/null || true
    fi
done

# Dropbear initramfs options (disable password auth, allow only key auth)
mkdir -p /root/.ssh
echo 'DROPBEAR_OPTIONS="-p 22 -s -j -k -w"' > /etc/dropbear/initramfs/dropbear.conf
echo "${PUBKEY_CONTENT}" > /etc/dropbear/initramfs/authorized_keys
echo "${PUBKEY_CONTENT}" > /root/.ssh/authorized_keys
chmod 600 /etc/dropbear/initramfs/authorized_keys /root/.ssh/authorized_keys 2>/dev/null || true

# Make sure MODULES=most so QEMU virtio/net/blk drivers are included
sed -i 's/^MODULES=.*/MODULES=most/' /etc/initramfs-tools/initramfs.conf 2>/dev/null || true

# Add required kernel modules for QEMU and crypto
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

# Install our custom unlock helper & passfifo script into initramfs hooks
mkdir -p /etc/initramfs-tools/hooks /etc/initramfs-tools/scripts/local-top

# Hook to copy /bin/unlock helper and setup passfifo support
cat << 'EOF' > /etc/initramfs-tools/hooks/nadamu_unlock
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

mkdir -p "${DESTDIR}/lib/cryptsetup" "${DESTDIR}/root/.ssh" "${DESTDIR}/etc/dropbear"
[ -p "${DESTDIR}/lib/cryptsetup/passfifo" ] || mkfifo "${DESTDIR}/lib/cryptsetup/passfifo"
chmod 600 "${DESTDIR}/lib/cryptsetup/passfifo"

# Ensure authorized_keys are copied to all potential dropbear search paths
if [ -f /etc/dropbear/initramfs/authorized_keys ]; then
    cp -f /etc/dropbear/initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
    cp -f /etc/dropbear/initramfs/authorized_keys "${DESTDIR}/etc/dropbear/authorized_keys" 2>/dev/null || true
fi

# Explicitly copy host keys
cp -f /etc/dropbear/initramfs/dropbear_*_host_key "${DESTDIR}/etc/dropbear/" 2>/dev/null || true

# Copy /bin/unlock
if [ -f /lab/laptop/bin/unlock ]; then
    copy_exec /lab/laptop/bin/unlock /bin/unlock
fi

# Ensure cryptsetup binaries and libs are present
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
echo "[*] Waiting for passphrase on /lib/cryptsetup/passfifo..."

# Read pass from FIFO and open cryptroot
while [ ! -b /dev/mapper/test_crypt ]; do
    if [ -p /lib/cryptsetup/passfifo ]; then
        PASS=$(cat /lib/cryptsetup/passfifo 2>/dev/null)
        if [ -n "$PASS" ]; then
            echo "[*] Received passphrase, attempting cryptsetup open..."
            printf "%s" "$PASS" | cryptsetup open --type luks /dev/vda test_crypt -
            if [ -b /dev/mapper/test_crypt ]; then
                echo "[+] LUKS device test_crypt opened successfully!"
                # Format if new
                if ! blkid /dev/mapper/test_crypt | grep -q ext4; then
                    echo "[*] Formatting ext4 rootfs..."
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

# Build standard Kali initramfs using update-initramfs
echo "[*] 6. Building official Kali initramfs image via update-initramfs..."
update-initramfs -u -k "${KERNEL_VER}" || update-initramfs -c -k "${KERNEL_VER}"

# Copy the generated initramfs to data directory
cp -f "/boot/initrd.img-${KERNEL_VER}" "${DATA_DIR}/test_initrd.img"

echo "[*] 7. Lab assets built successfully in ${DATA_DIR}"
ls -lh "${DATA_DIR}"
