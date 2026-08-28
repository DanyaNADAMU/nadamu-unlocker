#!/bin/sh
set -e

DATA_DIR="/lab/data"
KEYS_DIR="${DATA_DIR}/keys"
LUKS_PASSWORD="${LUKS_PASSWORD:-password}"

mkdir -p "${DATA_DIR}" "${KEYS_DIR}"

echo "=== [NADAMU LAB INITIALIZATION (KALI STANDARD UPDATE-INITRAMFS)] ==="

# 1. Generate Ed25519 key (only if missing; survives rebuilds so baked-in
#    authorized_keys and external SSH clients stay valid. Force rotation
#    with: touch ${DATA_DIR}/cmd.rekey)
if [ -f "${DATA_DIR}/cmd.rekey" ]; then
    echo "[*] 1. Rekey requested, rotating Ed25519 key..."
    rm -f "${DATA_DIR}/cmd.rekey"
    rm -f "${KEYS_DIR}/id_ed25519"*
fi

if [ -f "${KEYS_DIR}/id_ed25519" ] && [ -f "${KEYS_DIR}/id_ed25519.pub" ]; then
    echo "[*] 1. Reusing existing Ed25519 key (${KEYS_DIR}/id_ed25519)"
else
    echo "[*] 1. Generating fresh test SSH key (Ed25519)..."
    # Force empty passphrase with -N "" and quiet mode
    ssh-keygen -q -t ed25519 -N "" -f "${KEYS_DIR}/id_ed25519" -C "nadamu-test-ed25519"
fi

# Explicitly set permissions on the host side so SSH client doesn't complain.
# Owner is inherited from the data dir so that bind-mount consumers (e.g. an
# agent sandbox with its own uid mapping) can actually read the private key;
# container root stays able to read regardless via CAP_DAC_OVERRIDE.
KEY_UID="$(stat -c %u "${DATA_DIR}" 2>/dev/null || echo 0)"
KEY_GID="$(stat -c %g "${DATA_DIR}" 2>/dev/null || echo 0)"
chown "${KEY_UID}:${KEY_GID}" "${KEYS_DIR}/id_ed25519" "${KEYS_DIR}/id_ed25519.pub" 2>/dev/null || true
chmod 0600 "${KEYS_DIR}/id_ed25519"
chmod 0644 "${KEYS_DIR}/id_ed25519.pub"

PUBKEY_ED25519=$(cat "${KEYS_DIR}/id_ed25519.pub")

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
mkdir -p /etc/dropbear-initramfs /etc/dropbear/initramfs /root/.ssh
echo 'DROPBEAR_OPTIONS="-p 22 -s -j -k -E"' > /etc/dropbear/initramfs/dropbear.conf
echo 'DROPBEAR_OPTIONS="-p 22 -s -j -k -E"' > /etc/dropbear-initramfs/config

# Add key to authorized_keys
echo "${PUBKEY_ED25519}" > /etc/dropbear/initramfs/authorized_keys

cp -f /etc/dropbear/initramfs/authorized_keys /etc/dropbear-initramfs/authorized_keys
chmod 0600 /etc/dropbear/initramfs/authorized_keys /etc/dropbear-initramfs/authorized_keys

# Inject runtime permission fix into the dropbear startup script!
# This guarantees that right before dropbear starts, permissions are perfect.
if [ -f /usr/share/initramfs-tools/scripts/init-premount/dropbear ]; then
    sed -i '2i \
echo "[*] Fixing Dropbear permissions at runtime..." > /dev/console \
mkdir -p /root/.ssh \
chown -R 0:0 /root /etc/dropbear 2>/dev/null || true \
chmod 0700 /root /root/.ssh /etc/dropbear 2>/dev/null || true \
chmod 0600 /root/.ssh/authorized_keys 2>/dev/null || true \
' /usr/share/initramfs-tools/scripts/init-premount/dropbear
fi

# Make sure MODULES=most so QEMU virtio/net/blk drivers are included
sed -i 's/^MODULES=.*/MODULES=most/' /etc/initramfs-tools/initramfs.conf 2>/dev/null || true

cat << 'EOF' > /etc/initramfs-tools/modules
virtio_pci
virtio_net
virtio_blk
cfg80211
mac80211
rfkill
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

mkdir -p "${DESTDIR}/lib/cryptsetup" "${DESTDIR}/root/.ssh" "${DESTDIR}/etc"
[ -p "${DESTDIR}/lib/cryptsetup/passfifo" ] || mkfifo "${DESTDIR}/lib/cryptsetup/passfifo"
chmod 600 "${DESTDIR}/lib/cryptsetup/passfifo"

# Force copy authorized_keys
if [ -f /etc/dropbear/initramfs/authorized_keys ]; then
    cp -f /etc/dropbear/initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
elif [ -f /etc/dropbear-initramfs/authorized_keys ]; then
    cp -f /etc/dropbear-initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
fi

# Unlock root account in shadow if it exists (Dropbear might reject locked accounts)
if [ -f "${DESTDIR}/etc/shadow" ]; then
    sed -i 's/^root:[^:]*:/root::/' "${DESTDIR}/etc/shadow"
fi
# Also unlock in passwd just in case
if [ -f "${DESTDIR}/etc/passwd" ]; then
    sed -i 's/^root:x:/root::/' "${DESTDIR}/etc/passwd"
    sed -i 's/^root:\*:/root::/' "${DESTDIR}/etc/passwd"
fi

# Ensure /bin/sh is in /etc/shells so Dropbear allows login
echo "/bin/sh" >> "${DESTDIR}/etc/shells"

if [ -f /lab/laptop/bin/unlock ]; then
    copy_exec /lab/laptop/bin/unlock /bin/unlock
fi
copy_exec /sbin/cryptsetup /sbin/cryptsetup
exit 0
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_unlock

# Hook for Wi-Fi tools (wpa_supplicant, rfkill, iw) in lab
cat << 'EOF' > /etc/initramfs-tools/hooks/nadamu_wifi
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

if command -v wpa_supplicant >/dev/null 2>&1; then
    copy_exec /sbin/wpa_supplicant /sbin/wpa_supplicant 2>/dev/null || copy_exec /usr/sbin/wpa_supplicant /sbin/wpa_supplicant
fi
if command -v rfkill >/dev/null 2>&1; then
    copy_exec /sbin/rfkill /sbin/rfkill 2>/dev/null || copy_exec /usr/sbin/rfkill /sbin/rfkill
fi
if command -v iw >/dev/null 2>&1; then
    copy_exec /sbin/iw /sbin/iw 2>/dev/null || copy_exec /usr/sbin/iw /sbin/iw
fi
exit 0
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_wifi

# Script to unlock LUKS via passfifo during local-top
cat << 'EOF' > /etc/initramfs-tools/scripts/local-top/nadamu_cryptroot
#!/bin/sh
PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

mkdir -p /lib/cryptsetup
[ -p /lib/cryptsetup/passfifo ] || mkfifo /lib/cryptsetup/passfifo

echo "=========================================="
echo "    NADAMU LUKS UNREADY"
echo "=========================================="
echo "[*] Debug: Checking Dropbear authorized_keys in initramfs:"
ls -ld /root || echo "No /root"
ls -ld /root/.ssh || echo "No /root/.ssh"
ls -l /root/.ssh/authorized_keys || echo "No authorized_keys"

echo "[*] Waiting for passphrase on /lib/cryptsetup/passfifo..."

while true; do
    if [ -p /lib/cryptsetup/passfifo ]; then
        PASS=$(cat /lib/cryptsetup/passfifo 2>/dev/null)
        if [ -n "$PASS" ]; then
            echo "[*] Received passphrase, attempting cryptsetup open..."
            printf "%s" "$PASS" | cryptsetup open --type luks /dev/vda test_crypt -
            if [ -b /dev/mapper/test_crypt ]; then
                echo "[+] LUKS device test_crypt opened successfully!"
                sleep 2
                echo "[+] Test finished successfully. Shutting down VM..."
                poweroff -f || reboot -f
                exit 0
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
