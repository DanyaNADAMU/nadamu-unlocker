#!/bin/sh
# unlocker installer for laptop (Kali / Debian)
# Supports Local Modes: A1 (USB Tethering), A2 (Ethernet LAN), A3/A4 (Wi-Fi Hotspot / LAN)
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "[-] This script must be run as root (sudo ./install.sh)"
    exit 1
fi

WIFI_SSID="${WIFI_SSID:-}"
WIFI_PSK="${WIFI_PSK:-}"

# Parse command line options
while [ "$#" -gt 0 ]; do
    case "$1" in
        --ssid)
            WIFI_SSID="$2"
            shift 2
            ;;
        --psk|--password)
            WIFI_PSK="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

echo "=== [unlocker] Installing hooks for Kali/Debian (Modes A1/A2/A3) ==="

# 1. Check and install required packages
REQUIRED_PKGS="dropbear-initramfs cryptsetup-initramfs busybox wpasupplicant rfkill iw"
MISSING_PKGS=""
for pkg in $REQUIRED_PKGS; do
    if ! dpkg -s "$pkg" >/dev/null 2>&1; then
        MISSING_PKGS="$MISSING_PKGS $pkg"
    fi
done

if [ -n "$MISSING_PKGS" ]; then
    echo "[*] Installing missing dependencies:$MISSING_PKGS..."
    apt-get update -qq && apt-get install -y $MISSING_PKGS
fi

# 2. Configure network and dropbear in initramfs.conf
INITRAMFS_CONF="/etc/initramfs-tools/initramfs.conf"
if [ -f "$INITRAMFS_CONF" ]; then
    if ! grep -q "^DROPBEAR=y" "$INITRAMFS_CONF"; then
        echo "DROPBEAR=y" >> "$INITRAMFS_CONF"
    fi
    if ! grep -q "^IP=" "$INITRAMFS_CONF"; then
        echo "IP=dhcp" >> "$INITRAMFS_CONF"
        echo "[+] Configured initramfs for network DHCP."
    fi
fi

# Ensure network, wireless, and crypto modules in initramfs
MODULES_CONF="/etc/initramfs-tools/modules"
if [ -f "$MODULES_CONF" ]; then
    MODULE_LIST="virtio_pci virtio_net e1000e r8169 cfg80211 mac80211 rfkill dm_mod dm_crypt aes xts sha256 sha512"
    for mod in $MODULE_LIST; do
        if ! grep -q "^$mod" "$MODULES_CONF"; then
            echo "$mod" >> "$MODULES_CONF"
        fi
    done
fi

# 3. Create required directories
mkdir -p /etc/initramfs-tools/hooks
mkdir -p /etc/initramfs-tools/scripts/init-premount
mkdir -p /etc/initramfs-tools/scripts/local-top
mkdir -p /etc/dropbear-initramfs /etc/dropbear/initramfs /root/.ssh /etc/nadamu/wifi

# Copy local unlock helper
cp bin/unlock /usr/local/bin/unlock
chmod 755 /usr/local/bin/unlock

# 4. Optional Wi-Fi Hotspot / AP configuration (Mode A3 / A4)
if [ -z "$WIFI_SSID" ] && [ -t 0 ]; then
    echo ""
    printf "Configure Wi-Fi Hotspot for wireless unlock (Mode A3)? [y/N]: "
    read -r configure_wifi || configure_wifi="n"
    if [ "$configure_wifi" = "y" ] || [ "$configure_wifi" = "Y" ]; then
        printf "Enter Phone Wi-Fi Hotspot SSID: "
        read -r WIFI_SSID
        printf "Enter Hotspot WPA2 Password (PSK): "
        read -r WIFI_PSK
    fi
fi

if [ -n "$WIFI_SSID" ] && [ -n "$WIFI_PSK" ]; then
    echo "[*] Writing Wi-Fi configuration for SSID: $WIFI_SSID..."
    cat << EOF > /etc/nadamu/wifi/wpa_supplicant.conf
ctrl_interface=/run/wpa_supplicant
update_config=1

network={
    ssid="$WIFI_SSID"
    psk="$WIFI_PSK"
    key_mgmt=WPA-PSK
}
EOF
    chmod 600 /etc/nadamu/wifi/wpa_supplicant.conf
    echo "[+] Wi-Fi configuration saved (/etc/nadamu/wifi/wpa_supplicant.conf)."
fi

# 5. Create initramfs hook for LUKS passfifo and helper
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

# Copy authorized_keys to root's ssh directory in initramfs
if [ -f /etc/dropbear/initramfs/authorized_keys ]; then
    cp -f /etc/dropbear/initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
elif [ -f /etc/dropbear-initramfs/authorized_keys ]; then
    cp -f /etc/dropbear-initramfs/authorized_keys "${DESTDIR}/root/.ssh/authorized_keys"
fi

# Ensure /etc/shells exists and root account has a valid shell for Dropbear
echo "/bin/sh" >> "${DESTDIR}/etc/shells"
if [ -f "${DESTDIR}/etc/shadow" ]; then
    sed -i 's/^root:[^:]*:/root::/' "${DESTDIR}/etc/shadow"
fi
if [ -f "${DESTDIR}/etc/passwd" ]; then
    sed -i 's/^root:x:/root::/' "${DESTDIR}/etc/passwd"
    sed -i 's/^root:\*:/root::/' "${DESTDIR}/etc/passwd"
fi

copy_exec /usr/local/bin/unlock /bin/unlock
copy_exec /sbin/cryptsetup /sbin/cryptsetup 2>/dev/null || true
exit 0
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_unlock

# 6. Create initramfs hook for Wi-Fi support (Mode A3/A4)
cat << 'EOF' > /etc/initramfs-tools/hooks/nadamu_wifi
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

# Copy wpa_supplicant and wireless tools if available
if command -v wpa_supplicant >/dev/null 2>&1; then
    copy_exec /sbin/wpa_supplicant /sbin/wpa_supplicant 2>/dev/null || copy_exec /usr/sbin/wpa_supplicant /sbin/wpa_supplicant
fi
if command -v rfkill >/dev/null 2>&1; then
    copy_exec /sbin/rfkill /sbin/rfkill 2>/dev/null || copy_exec /usr/sbin/rfkill /sbin/rfkill
fi
if command -v iw >/dev/null 2>&1; then
    copy_exec /sbin/iw /sbin/iw 2>/dev/null || copy_exec /usr/sbin/iw /sbin/iw
fi

# Copy wpa_supplicant config if configured
if [ -f /etc/nadamu/wifi/wpa_supplicant.conf ]; then
    mkdir -p "${DESTDIR}/etc/wpa_supplicant"
    cp -f /etc/nadamu/wifi/wpa_supplicant.conf "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
    chmod 600 "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
fi

# Copy wireless regulatory DB if present
for reg in /lib/firmware/regulatory.db* /lib/crda/regulatory.bin*; do
    if [ -f "$reg" ]; then
        mkdir -p "${DESTDIR}$(dirname "$reg")"
        cp -f "$reg" "${DESTDIR}$reg"
    fi
done

exit 0
EOF
chmod +x /etc/initramfs-tools/hooks/nadamu_wifi

# 7. Create premount script for Dropbear runtime permissions
cat << 'EOF' > /etc/initramfs-tools/scripts/init-premount/nadamu_dropbear_perms
#!/bin/sh
PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

mkdir -p /root/.ssh /etc/dropbear
chown -R 0:0 /root /etc/dropbear 2>/dev/null || true
chmod 0700 /root /root/.ssh /etc/dropbear 2>/dev/null || true
[ -f /root/.ssh/authorized_keys ] && chmod 0600 /root/.ssh/authorized_keys
exit 0
EOF
chmod +x /etc/initramfs-tools/scripts/init-premount/nadamu_dropbear_perms

# 8. Create premount script for Wi-Fi activation (Mode A3/A4)
cat << 'EOF' > /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up
#!/bin/sh
PREREQ="udev"
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

CONF="/etc/wpa_supplicant/wpa_supplicant.conf"
[ -f "$CONF" ] || exit 0

echo "[nadamu-wifi] Initializing wireless interface..."
rfkill unblock wifi 2>/dev/null || rfkill unblock all 2>/dev/null || true

WLAN_IF=""
for ifpath in /sys/class/net/*; do
    if [ -d "$ifpath/wireless" ] || [ -d "$ifpath/phy80211" ]; then
        WLAN_IF=$(basename "$ifpath")
        break
    fi
done

if [ -z "$WLAN_IF" ]; then
    for ifname in wlan0 wlan1 wlp2s0 wlp3s0 wlo1; do
        if [ -d "/sys/class/net/$ifname" ]; then
            WLAN_IF="$ifname"
            break
        fi
    done
fi

if [ -z "$WLAN_IF" ]; then
    echo "[nadamu-wifi] No wireless interface detected."
    exit 0
fi

echo "[nadamu-wifi] Bringing up wireless interface $WLAN_IF..."
ip link set "$WLAN_IF" up 2>/dev/null || true

mkdir -p /run /var/run
wpa_supplicant -B -i "$WLAN_IF" -c "$CONF" -P /run/wpa_supplicant.pid 2>/dev/null || true

# Wait for association (up to 8 seconds)
i=0
while [ $i -lt 8 ]; do
    if [ -f "/sys/class/net/$WLAN_IF/carrier" ] && [ "$(cat "/sys/class/net/$WLAN_IF/carrier" 2>/dev/null)" = "1" ]; then
        echo "[nadamu-wifi] Wi-Fi associated on $WLAN_IF."
        break
    fi
    sleep 1
    i=$((i + 1))
done

# Acquire DHCP lease on wireless interface
echo "[nadamu-wifi] Requesting DHCP lease on $WLAN_IF..."
udhcpc -i "$WLAN_IF" -n -q -t 5 2>/dev/null || true
exit 0
EOF
chmod +x /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up

# 9. Create local-top watcher script for passfifo
cat << 'EOF' > /etc/initramfs-tools/scripts/local-top/nadamu_cryptroot
#!/bin/sh
PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

mkdir -p /lib/cryptsetup
[ -p /lib/cryptsetup/passfifo ] || mkfifo /lib/cryptsetup/passfifo
chmod 600 /lib/cryptsetup/passfifo

echo "[nadamu] Waiting for LUKS passphrase on /lib/cryptsetup/passfifo..."
exit 0
EOF
chmod +x /etc/initramfs-tools/scripts/local-top/nadamu_cryptroot

# 10. Configure Dropbear initramfs options (disable password auth, pubkey only)
DROPBEAR_OPTS='DROPBEAR_OPTIONS="-p 22 -s -j -k -E"'
for conf_path in /etc/dropbear/initramfs/dropbear.conf /etc/dropbear-initramfs/config; do
    mkdir -p "$(dirname "$conf_path")"
    if [ -f "$conf_path" ]; then
        grep -v "DROPBEAR_OPTIONS=" "$conf_path" > "${conf_path}.tmp" || true
        echo "$DROPBEAR_OPTS" >> "${conf_path}.tmp"
        mv "${conf_path}.tmp" "$conf_path"
    else
        echo "$DROPBEAR_OPTS" > "$conf_path"
    fi
done
echo "[+] Configured Dropbear for public-key authentication only (-s -j -k)."

# 11. Rebuild initramfs
echo "[*] Updating initramfs image..."
update-initramfs -u -k all

echo "[+] unlocker installation completed successfully!"
echo "[*] Next step: add your client public key to /etc/dropbear/initramfs/authorized_keys"
