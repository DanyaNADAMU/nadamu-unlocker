#!/bin/sh
set -e

# Config
PASS="${1:-nadamu-test-pass-1234}"
DISK_IMG="/lab/test_disk.img"
INITRD_DIR="/lab/initrd_build"
INITRD_OUT="/lab/test_initrd.img"

echo "[*] 1. Generating test SSH keys..."
mkdir -p /lab/keys
if [ ! -f /lab/keys/id_ed25519 ]; then
    ssh-keygen -t ed25519 -N "" -f /lab/keys/id_ed25519 -C "nadamu-test-client"
fi

echo "[*] 2. Creating raw disk image (500MB sparse)..."
rm -f "$DISK_IMG"
truncate -s 500M "$DISK_IMG"

echo "[*] 3. Formatting disk with LUKS2 (cryptsetup)..."
# Use PBKDF Argon2id with lower memory limit for fast lab testing
printf "%s" "$PASS" | cryptsetup luksFormat --type luks2 --pbkdf argon2id --pbkdf-memory 65536 --pbkdf-force-iterations 4 --batch-mode "$DISK_IMG"

echo "[*] 4. Opening LUKS device and creating ext4 rootfs..."
MAPPER_NAME="test_crypt_builder"
printf "%s" "$PASS" | cryptsetup open "$DISK_IMG" "$MAPPER_NAME"

mkfs.ext4 -L rootfs "/dev/mapper/$MAPPER_NAME"
mkdir -p /mnt/test_root
mount "/dev/mapper/$MAPPER_NAME" /mnt/test_root

# Minimal rootfs marker
mkdir -p /mnt/test_root/etc /mnt/test_root/bin /mnt/test_root/sbin /mnt/test_root/dev /mnt/test_root/proc /mnt/test_root/sys
echo "SUCCESS_UNLOCKED_NADAMU" > /mnt/test_root/etc/BOOT_SUCCESS

# Simple init script inside rootfs to confirm boot completion
cat << 'EOF' > /mnt/test_root/sbin/init
#!/bin/sh
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev
echo ""
echo "=========================================="
echo " [NADAMU] BOOT SUCCESSFUL! ROOTFS MOUNTED!"
echo " Flag: $(cat /etc/BOOT_SUCCESS)"
echo "=========================================="
echo ""
while true; do sleep 3600; done
EOF
chmod +x /mnt/test_root/sbin/init

umount /mnt/test_root
cryptsetup close "$MAPPER_NAME"
echo "[+] LUKS disk image ready: $DISK_IMG"

echo "[*] 5. Building test initramfs..."
rm -rf "$INITRD_DIR"
mkdir -p "$INITRD_DIR"/bin "$INITRD_DIR"/sbin "$INITRD_DIR"/etc "$INITRD_DIR"/proc "$INITRD_DIR"/sys "$INITRD_DIR"/dev "$INITRD_DIR"/run "$INITRD_DIR"/lib "$INITRD_DIR"/lib64 "$INITRD_DIR"/lib/cryptsetup "$INITRD_DIR"/etc/dropbear

# Copy static busybox
cp /bin/busybox "$INITRD_DIR"/bin/
for applet in sh ash ls cat echo printf sleep mkdir mount umount mknod killall ps grep stty ping ip ifconfig udhcpc; do
    ln -s busybox "$INITRD_DIR"/bin/$applet 2>/dev/null || true
done

# Copy cryptsetup, dropbear, and their shared library dependencies
copy_with_libs() {
    bin_path="$1"
    [ ! -f "$bin_path" ] && return
    cp "$bin_path" "$INITRD_DIR/bin/"
    ldd "$bin_path" 2>/dev/null | grep -o '/[^ ]*' | while read -r lib; do
        if [ -f "$lib" ]; then
            target_dir="$INITRD_DIR$(dirname "$lib")"
            mkdir -p "$target_dir"
            cp -u "$lib" "$target_dir/" 2>/dev/null || true
        fi
    done
}

copy_with_libs "$(which cryptsetup)"
copy_with_libs "$(which dropbear)"
copy_with_libs "$(which dropbearkey)"

# Generate host keys for dropbear
if [ ! -f "$INITRD_DIR/etc/dropbear/dropbear_ed25519_host_key" ]; then
    dropbearkey -t ed25519 -f "$INITRD_DIR/etc/dropbear/dropbear_ed25519_host_key"
fi

# Authorized keys
mkdir -p "$INITRD_DIR/root/.ssh"
cat /lab/keys/id_ed25519.pub > "$INITRD_DIR/root/.ssh/authorized_keys"
chmod 600 "$INITRD_DIR/root/.ssh/authorized_keys"

# Copy custom unlock CLI wrapper
cat << 'EOF' > "$INITRD_DIR/bin/unlock"
#!/bin/sh
stty -echo
printf "Enter LUKS Password: "
read -r PASS
stty echo
printf "\n"

FIFO=""
if [ -p /lib/cryptsetup/passfifo ]; then
    FIFO="/lib/cryptsetup/passfifo"
elif [ -p /run/cryptsetup/passfifo ]; then
    FIFO="/run/cryptsetup/passfifo"
fi

if [ -n "$FIFO" ]; then
    printf "%s" "$PASS" > "$FIFO"
    echo "[NADAMU] Unlock payload sent to $FIFO"
else
    echo "Error: passfifo not found"
fi
EOF
chmod +x "$INITRD_DIR/bin/unlock"

# Custom init script for initramfs
cat << 'EOF' > "$INITRD_DIR/init"
#!/bin/sh
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev
mkdir -p /dev/pts /dev/shm
mount -t devpts devpts /dev/pts

echo "=========================================="
echo " [NADAMU LAB] Starting Initramfs..."
echo "=========================================="

# Bring up network
ip link set lo up
ip link set eth0 up 2>/dev/null || ip link set enp0s3 up 2>/dev/null || true
udhcpc -i eth0 -n -q -t 2 2>/dev/null || true

# Start Dropbear SSH Daemon (key auth only, port 22)
mkdir -p /var/run /var/log
echo "root:x:0:0:root:/root:/bin/sh" > /etc/passwd
echo "root:*:19000:0:99999:7:::" > /etc/shadow
dropbear -s -j -k -p 22 -r /etc/dropbear/dropbear_ed25519_host_key

echo "[NADAMU LAB] Dropbear SSH running on port 22."
echo "[NADAMU LAB] Waiting for LUKS unlock via /lib/cryptsetup/passfifo..."

# Create FIFO
mkdir -p /lib/cryptsetup
rm -f /lib/cryptsetup/passfifo
mknod /lib/cryptsetup/passfifo p

# Wait for unlock loop
UNLOCKED=0
while [ $UNLOCKED -eq 0 ]; do
    # Read from passfifo and try cryptsetup
    cryptsetup open /dev/vda test_crypt --key-file=/lib/cryptsetup/passfifo
    if [ -e /dev/mapper/test_crypt ]; then
        echo "[NADAMU LAB] Successfully decrypted /dev/vda -> /dev/mapper/test_crypt!"
        UNLOCKED=1
    else
        echo "[NADAMU LAB] Decryption failed, retrying passfifo..."
    fi
done

# Kill dropbear before switch_root
killall dropbear 2>/dev/null || true

# Mount real root and switch_root
mkdir -p /newroot
mount /dev/mapper/test_crypt /newroot

echo "[NADAMU LAB] Handing over execution to real rootfs..."
exec switch_root /newroot /sbin/init
EOF
chmod +x "$INITRD_DIR/init"

# Pack initramfs (cpio.gz)
cd "$INITRD_DIR"
find . -print0 | cpio --null -ov --format=newc | gzip -9 > "$INITRD_OUT"
echo "[+] Initramfs packed successfully: $INITRD_OUT"
