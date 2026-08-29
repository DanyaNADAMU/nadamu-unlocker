# Laptop Setup Guide (Target Machine)

[ English ](laptop-setup.md) • [ Русский ](../ru/laptop-setup.md)

> Comprehensive step-by-step guide for setting up LUKS remote unlocking on Kali Linux / Debian / Ubuntu laptops.
> Configures `dropbear-initramfs`, kernel modules, Wi-Fi hooks, and unlock parameters.
>
> Verified: 2026-08-28 (tested via lab test_unlock.py against multi-transport QEMU test harness)

---

## 1. Boot Architecture and Lifecycle

When powering on an encrypted system or resuming from encrypted swap/hibernation:

```
[ Power On / Resume from Swap ]
               │
               ▼
     [ UEFI / BIOS / GRUB ]
               │
               ▼
      [ Linux Kernel Boot ]
               │
               ▼
      [ Initramfs Stage ] ──► Loads network & crypto drivers (USB, Ethernet, Wi-Fi)
               │          ──► Brings up network via DHCP (IP=dhcp / udhcpc)
               │          ──► Starts Dropbear SSH server on port 22 (pubkey auth only)
               │          ──► Creates named pipe (FIFO): /lib/cryptsetup/passfifo
               │          ──► cryptsetup waits for passphrase from passfifo or console
               │
               ▼  ◄── Phone connects via SSH, writes passphrase into passfifo
  [ LUKS Volume Unlocked ] ──► Target block device appears at /dev/mapper/<target>
               │
               ▼
     [ Pivot_root to Real OS ] ──► Initramfs network & Dropbear cleanly terminated
               │               ──► Mounts encrypted root, systemd starts user space
               ▼
     [ Login Screen / Desktop ]
```

### Why Dropbear in Initramfs?
- At boot time, the root filesystem is still encrypted. Standard services (`sshd`, `systemd`, `NetworkManager`) are not yet available.
- `dropbear-initramfs` is a standalone, lightweight SSH server compiled to run directly from the RAM-backed initramfs environment.
- Passphrase injection uses the FIFO pipe `/lib/cryptsetup/passfifo`. Any valid passphrase written to this pipe unlocks the volume without physical keyboard interaction.

---

## 2. Prerequisites and Required Packages

| Package | Purpose in Initramfs |
|---|---|
| `dropbear-initramfs` | Embedded SSH server for the pre-boot environment. |
| `cryptsetup-initramfs` | Hooks and scripts for LUKS unlock and `passfifo` creation. |
| `busybox` | Minimal UNIX utilities (`sh`, `udhcpc`, `ip`, `printf`, `test`). |
| `wpasupplicant` | WPA/WPA2 Wi-Fi client for pre-boot wireless connection (Modes A3/A4). |
| `rfkill` | Utility to unblock wireless hardware/software switches. |
| `iw` | Wireless device configuration and scanning utility. |

Install all prerequisites:
```sh
sudo apt-get update
sudo apt-get install -y dropbear-initramfs cryptsetup-initramfs busybox wpasupplicant rfkill iw
```

---

## 3. Configuring Local Unlock Modes (A1, A2, A3, A4)

### Mode A1: USB Tethering (Phone ⇄ USB Cable ⇄ Laptop)

**Topology:** Laptop is connected to phone via standard USB Type-C cable. USB Tethering is enabled on the phone; the phone acts as a DHCP server and router for the laptop.

#### Step 1. Add Kernel Network Modules
Edit `/etc/initramfs-tools/modules` and append USB networking drivers:
```conf
rndis_host
cdc_ether
cdc_ncm
usbnet
```
*Details:* `rndis_host` supports Android RNDIS tethering; `cdc_ether` and `cdc_ncm` provide standard USB-Ethernet CDC connectivity.

#### Step 2. Enable Network and Dropbear in Initramfs
In `/etc/initramfs-tools/initramfs.conf`:
```conf
DROPBEAR=y
IP=dhcp
```

#### Step 3. Configure Dropbear Security (Pubkey Auth Only)
In `/etc/dropbear-initramfs/config` (or `/etc/dropbear/initramfs/dropbear.conf`):
```conf
DROPBEAR_OPTIONS="-p 22 -s -j -k -E"
```
*Flag Breakdown:*
- `-p 22`: Listen on port 22.
- `-s`: Disable password authentication (force public key authentication).
- `-j`: Disable local port forwarding.
- `-k`: Disable remote port forwarding.
- `-E`: Log errors to standard error / initramfs console.

#### Step 4. Add Phone Client Public Key
Copy the public key generated in the Nadamu Android app into `/etc/dropbear/initramfs/authorized_keys` (or `/etc/dropbear-initramfs/authorized_keys`):
```sh
sudo chmod 600 /etc/dropbear/initramfs/authorized_keys
```

#### Step 5. Rebuild Initramfs
```sh
sudo update-initramfs -u -k all
```
*Flag Breakdown:*
- `-u`: Update existing initramfs.
- `-k all`: Rebuild initramfs for all installed Linux kernel versions.

---

### Mode A2: Ethernet LAN (Laptop on Wired Network)

**Topology:** Laptop is connected to the same local router/switch via Ethernet cable as the phone (via Wi-Fi on the same subnet).

#### Step 1. Ensure Ethernet Driver is in Initramfs
Identify your Ethernet NIC module (e.g. `lspci -k` or `lsmod | grep -E "r8169|e1000e|tg3|igb"`).
Add your NIC driver name (e.g., `r8169`, `e1000e`, `virtio_net`) to `/etc/initramfs-tools/modules`.

#### Step 2. Configuration and Build
Use the same `IP=dhcp`, Dropbear options (`-p 22 -s -j -k -E`), and `authorized_keys` as in Mode A1.
Rebuild:
```sh
sudo update-initramfs -u -k all
```

---

### Mode A3: Wi-Fi Hotspot (Laptop Joins Phone's Hotspot)

**Topology:** Turn on the Wi-Fi Hotspot on your phone. When the laptop boots, initramfs activates the Wi-Fi card, joins your phone's hotspot, gets an IP, and starts Dropbear.

#### Step 1. Wireless Kernel Modules
Add wireless subsystem modules to `/etc/initramfs-tools/modules`:
```conf
cfg80211
mac80211
rfkill
```
*(Ensure your Wi-Fi card driver, e.g. `iwlwifi`, `ath9k`, `rtw88`, is also loaded or built-in).*

#### Step 2. Save Hotspot Credentials
Create the configuration file `/etc/nadamu/wifi/wpa_supplicant.conf`:
```sh
sudo mkdir -p /etc/nadamu/wifi
sudo tee /etc/nadamu/wifi/wpa_supplicant.conf > /dev/null << 'EOF'
ctrl_interface=/run/wpa_supplicant
update_config=1

network={
    ssid="YOUR_PHONE_HOTSPOT_SSID"
    psk="YOUR_PHONE_HOTSPOT_PASSWORD"
    key_mgmt=WPA-PSK
}
EOF
sudo chmod 600 /etc/nadamu/wifi/wpa_supplicant.conf
```

#### Step 3. Create Initramfs Wi-Fi Hook (`nadamu_wifi`)
This hook copies `wpa_supplicant`, `iw`, `rfkill`, wireless regulatory databases (`regulatory.db`), and the saved configuration into the initramfs image:
```sh
sudo tee /etc/initramfs-tools/hooks/nadamu_wifi > /dev/null << 'EOF'
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

# Copy wpa_supplicant and wireless utilities
if command -v wpa_supplicant >/dev/null 2>&1; then
    copy_exec /sbin/wpa_supplicant /sbin/wpa_supplicant 2>/dev/null || copy_exec /usr/sbin/wpa_supplicant /sbin/wpa_supplicant
fi
if command -v rfkill >/dev/null 2>&1; then
    copy_exec /sbin/rfkill /sbin/rfkill 2>/dev/null || copy_exec /usr/sbin/rfkill /sbin/rfkill
fi
if command -v iw >/dev/null 2>&1; then
    copy_exec /sbin/iw /sbin/iw 2>/dev/null || copy_exec /usr/sbin/iw /sbin/iw
fi

# Copy wpa_supplicant config if present
if [ -f /etc/nadamu/wifi/wpa_supplicant.conf ]; then
    mkdir -p "${DESTDIR}/etc/wpa_supplicant"
    cp -f /etc/nadamu/wifi/wpa_supplicant.conf "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
    chmod 600 "${DESTDIR}/etc/wpa_supplicant/wpa_supplicant.conf"
fi

# Copy wireless regulatory DB (CRDA)
for reg in /lib/firmware/regulatory.db* /lib/crda/regulatory.bin*; do
    if [ -f "$reg" ]; then
        mkdir -p "${DESTDIR}$(dirname "$reg")"
        cp -f "$reg" "${DESTDIR}$reg"
    fi
done

exit 0
EOF
sudo chmod +x /etc/initramfs-tools/hooks/nadamu_wifi
```

#### Step 4. Create Initramfs Premount Script (`nadamu_wifi_up`)
This script runs at `init-premount` time during boot. It unblocks wireless devices via `rfkill unblock wifi`, brings up the interface, connects to the hotspot, and requests DHCP:
```sh
sudo tee /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up > /dev/null << 'EOF'
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
sudo chmod +x /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up
```

*Command Breakdown:*
- `wpa_supplicant -B -i "$WLAN_IF" -c "$CONF" -P /run/wpa_supplicant.pid`:
  - `-B`: Run in daemon / background mode.
  - `-i <iface>`: Specify wireless interface (e.g., `wlan0`, `wlp2s0`).
  - `-c <path>`: Path to configuration file inside initramfs.
  - `-P <pidfile>`: Save process ID to file.
- `udhcpc -i "$WLAN_IF" -n -q -t 5`:
  - `-i <iface>`: Target interface.
  - `-n`: Exit immediately if lease is not obtained (non-blocking).
  - `-q`: Quit immediately after obtaining a valid DHCP lease.
  - `-t 5`: Send up to 5 DHCP discover packets.

#### Step 5. Rebuild Initramfs
```sh
sudo update-initramfs -u -k all
```

---

### Mode A4: Same Home/Office Wi-Fi Network

**Topology:** Both phone and laptop connect to your existing home or office Wi-Fi router.

- Setup is identical to Mode A3, with `YOUR_HOME_WIFI_SSID` and `YOUR_HOME_WIFI_PASSWORD` specified in `/etc/nadamu/wifi/wpa_supplicant.conf`.
- **Important Router Requirement:** AP/Client Isolation (Guest Network Isolation) must be **disabled** on your Wi-Fi router, otherwise the router will prevent wireless clients from communicating with each other on port 22.

---

## 4. Automated Setup via Script

To apply all configuration steps automatically, run the installer script from the repo:

```sh
sudo ./install.sh
```

Or provide Wi-Fi credentials directly for Mode A3/A4:
```sh
sudo ./install.sh --ssid "MyHotspot" --psk "MyPassword123"
```

After running the installer:
1. Open the **Nadamu Unlocker** app on your phone.
2. Copy the **Client SSH Public Key**.
3. Paste it into `/etc/dropbear/initramfs/authorized_keys` on your laptop.
4. Run `sudo update-initramfs -u -k all`.

---

## 5. Interactive Verification

Verify that the `passfifo` pipe accepts input over SSH without rebooting:
```sh
ssh -i /path/to/private_key -p 22 root@<LAPTOP_IP>
```
In the initramfs shell, run the unlock helper:
```sh
# unlock
Enter LUKS Password:
[NADAMU] Unlock payload delivered to /lib/cryptsetup/passfifo.
```
Upon entering the correct passphrase, the `/dev/mapper/<target>` device will open, initramfs will yield control to systemd, and the OS boot will continue.
