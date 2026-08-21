#!/bin/sh
set -e

DATA_DIR="/lab/data"
KEYS_DIR="${DATA_DIR}/keys"
LUKS_PASS="${LUKS_PASSWORD:-password}"
DISK_IMG="${DATA_DIR}/test_disk.img"
INITRD_IMG="${DATA_DIR}/test_initrd.img"
VMLINUZ="${DATA_DIR}/vmlinuz"

mkdir -p "${DATA_DIR}" "${KEYS_DIR}"

echo "[*] 1. Generating test SSH keys..."
if [ ! -f "${KEYS_DIR}/id_ed25519" ]; then
    ssh-keygen -t ed25519 -N "" -C "nadamu-test-client" -f "${KEYS_DIR}/id_ed25519"
fi

echo "[*] 2. Creating raw disk image (500MB sparse)..."
if [ ! -f "${DISK_IMG}" ]; then
    truncate -s 500M "${DISK_IMG}"
    echo "[*] 3. Formatting disk with LUKS2 (cryptsetup)..."
    printf "%s" "${LUKS_PASS}" | cryptsetup luksFormat --type luks2 --batch-mode "${DISK_IMG}" -
fi

echo "[*] 4. Copying kernel binary..."
KERNEL_BIN=$(ls -1 /boot/vmlinuz-* 2>/dev/null | sort -V | tail -n 1 || true)
if [ -n "${KERNEL_BIN}" ] && [ -f "${KERNEL_BIN}" ]; then
    cp -f "${KERNEL_BIN}" "${VMLINUZ}"
else
    echo "Warning: /boot/vmlinuz-* not found in container."
fi

echo "[*] 5. Building custom test initramfs..."
WORK_INITRAMFS=$(mktemp -d /tmp/initramfs_build.XXXXXX)

mkdir -p "${WORK_INITRAMFS}/bin"          "${WORK_INITRAMFS}/sbin"          "${WORK_INITRAMFS}/lib"          "${WORK_INITRAMFS}/lib64"          "${WORK_INITRAMFS}/lib/cryptsetup"          "${WORK_INITRAMFS}/etc/dropbear"          "${WORK_INITRAMFS}/dev"          "${WORK_INITRAMFS}/proc"          "${WORK_INITRAMFS}/sys"          "${WORK_INITRAMFS}/run"          "${WORK_INITRAMFS}/tmp"          "${WORK_INITRAMFS}/root"          "${WORK_INITRAMFS}/newroot"

# Copy BusyBox and symlinks
cp -f /bin/busybox "${WORK_INITRAMFS}/bin/busybox"
chroot "${WORK_INITRAMFS}" /bin/busybox --install -s /bin

# Copy Dropbear & cryptsetup & mkfs.ext4 with shared libraries
for bin_path in /usr/sbin/dropbear /usr/bin/dropbearkey /sbin/cryptsetup /usr/sbin/mkfs.ext4 /sbin/mkfs.ext4; do
    if [ -f "${bin_path}" ]; then
        dest_dir="${WORK_INITRAMFS}$(dirname "${bin_path}")"
        mkdir -p "${dest_dir}"
        cp -f "${bin_path}" "${dest_dir}/"
        ldd "${bin_path}" | grep -o '/lib[^ ]*' | while read -r lib; do
            if [ -f "${lib}" ]; then
                lib_dest="${WORK_INITRAMFS}$(dirname "${lib}")"
                mkdir -p "${lib_dest}"
                cp -f -u "${lib}" "${lib_dest}/" 2>/dev/null || true
            fi
        done
    fi
done

# Copy SSH public key for Dropbear
cat "${KEYS_DIR}/id_ed25519.pub" > "${WORK_INITRAMFS}/etc/dropbear/authorized_keys"
chmod 600 "${WORK_INITRAMFS}/etc/dropbear/authorized_keys"

# Generate Dropbear host key inside initramfs
dropbearkey -t ed25519 -f "${WORK_INITRAMFS}/etc/dropbear/dropbear_ed25519_host_key" 2>/dev/null

# Create /etc/passwd and group
cat << 'EOF' > "${WORK_INITRAMFS}/etc/passwd"
root:x:0:0:root:/root:/bin/sh
EOF
cat << 'EOF' > "${WORK_INITRAMFS}/etc/group"
root:x:0:
EOF

# Create init script
cat << 'EOF' > "${WORK_INITRAMFS}/init"
#!/bin/sh
export PATH=/bin:/sbin:/usr/bin:/usr/sbin

# Mount virtual filesystems
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev 2>/dev/null || true

echo "=========================================="
echo "    NADAMU TEST INITRAMFS LOADED         "
echo "=========================================="

# Create FIFO for LUKS password injection
mkdir -p /lib/cryptsetup
mkfifo /lib/cryptsetup/passfifo

# Bring up loopback and network
ifconfig lo 127.0.0.1 up
ifconfig eth0 10.0.2.15 netmask 255.255.255.0 up 2>/dev/null || true
route add default gw 10.0.2.2 2>/dev/null || true

# Start Dropbear SSH Daemon
echo "[initramfs] Starting Dropbear on port 22..."
/usr/sbin/dropbear -E -s -j -k -p 22 -r /etc/dropbear/dropbear_ed25519_host_key

echo "[initramfs] Awaiting password in /lib/cryptsetup/passfifo..."

# Loop until disk is unlocked
UNLOCKED=0
while [ ${UNLOCKED} -eq 0 ]; do
    PASS=$(cat /lib/cryptsetup/passfifo)
    echo "[initramfs] Password received from passfifo. Attempting cryptsetup open..."
    if printf "%s" "${PASS}" | cryptsetup open --type luks /dev/vda test_crypt -; then
        echo "[initramfs] LUKS unlock SUCCESSFUL!"
        UNLOCKED=1
    else
        echo "[initramfs] cryptsetup unlock FAILED! Re-opening passfifo..."
    fi
done

# Check if filesystem exists on unlocked mapper, format if needed
echo "[initramfs] Checking root filesystem on /dev/mapper/test_crypt..."
if ! blkid /dev/mapper/test_crypt | grep -q "ext4"; then
    echo "[initramfs] First boot: Formatting /dev/mapper/test_crypt as ext4..."
    mkfs.ext4 -F /dev/mapper/test_crypt
fi

# Mount rootfs and create success flag
mount /dev/mapper/test_crypt /newroot
echo "NADAMU_BOOT_SUCCESS_$(date +%s)" > /newroot/BOOT_SUCCESS.txt
echo "[initramfs] Successfully mounted rootfs. BOOT_SUCCESS.txt written."

# Stop dropbear
killall dropbear 2>/dev/null || true

echo "=========================================="
echo "    NADAMU: BOOT CYCLE COMPLETE           "
echo "=========================================="

# Keep alive or clean exit
sync
poweroff -f
EOF

chmod +x "${WORK_INITRAMFS}/init"

# Package initramfs
(cd "${WORK_INITRAMFS}" && find . -print0 | cpio --null --create --format=newc | gzip -9 > "${INITRD_IMG}")
rm -rf "${WORK_INITRAMFS}"

echo "[*] 6. Lab assets built successfully in ${DATA_DIR}"
