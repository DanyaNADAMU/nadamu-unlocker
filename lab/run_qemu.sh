#!/bin/sh
set -e

DISK_IMG="/lab/data/test_disk.img"
INITRD_IMG="/lab/data/test_initrd.img"
KERNEL=$(ls -t /boot/vmlinuz-* 2>/dev/null | head -n 1)

if [ -z "$KERNEL" ] || [ ! -f "$KERNEL" ]; then
    echo "[-] Kernel not found in /boot/vmlinuz-*"
    exit 1
fi

echo "[*] Using kernel: $KERNEL"

# Check KVM availability
KVM_FLAG="-machine q35,accel=tcg"
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    echo "[+] /dev/kvm is readable/writable, testing KVM acceleration..."
    if qemu-system-x86_64 -enable-kvm -machine accel=kvm -display none -vga none 2>&1 | grep -iq "failed to initialize kvm\|permission denied"; then
        echo "[!] KVM failed permission test. Falling back to TCG software emulation."
        KVM_FLAG="-machine q35,accel=tcg"
    else
        echo "[+] KVM hardware acceleration enabled!"
        KVM_FLAG="-enable-kvm -cpu host"
    fi
else
    echo "[!] /dev/kvm not writable (or missing). Using TCG software emulation."
    KVM_FLAG="-machine q35,accel=tcg"
fi

echo "[*] Launching QEMU VM..."
exec qemu-system-x86_64 \
    $KVM_FLAG \
    -m 512M \
    -smp 1 \
    -nographic \
    -kernel "$KERNEL" \
    -initrd "$INITRD_IMG" \
    -drive file="$DISK_IMG",format=raw,if=virtio \
    -netdev user,id=net0,hostfwd=tcp::2222-:22 \
    -device virtio-net-pci,netdev=net0 \
    -append "console=ttyS0 root=/dev/mapper/test_crypt cryptopts=target=test_crypt,source=/dev/vda,lvm=none ip=dhcp boot=local panic=0" \
    -serial mon:stdio
