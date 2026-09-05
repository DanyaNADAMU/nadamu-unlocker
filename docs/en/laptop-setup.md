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
iwlwifi
iwlmvm
```
*(Ensure your Wi-Fi card driver, e.g. `iwlwifi`, `iwlmvm`, `rtw88_8822ce`, `ath10k_pci`, `mt7921e`, is also listed).*

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
    key_mgmt=WPA-PSK WPA-PSK-SHA256 SAE
    proto=RSN WPA
    pairwise=CCMP TKIP
    group=CCMP TKIP
    ieee80211w=1
    scan_ssid=1
}
EOF
sudo chmod 600 /etc/nadamu/wifi/wpa_supplicant.conf
```
*Crucial parameters:*
- `ieee80211w=1` (Protected Management Frames / PMF): **mandatory for modern Android hotspots**; without PMF, the hotspot rejects association with error `status_code=31` (`ASSOC_REJECT`).
- `key_mgmt=WPA-PSK WPA-PSK-SHA256 SAE`: supports both WPA2-Personal and WPA3-Personal.
- `scan_ssid=1`: forces active probing for mobile / hidden hotspots.
- If your Wi-Fi password contains special characters (`$`, `&`, `#`), pass them enclosed in single quotes `'...'` in the shell to prevent variable interpolation or backgrounding.
- WPA-PSK passphrase length standard: **8 to 63 characters**.

#### Step 3. Create Initramfs Wi-Fi Hook (`nadamu_wifi`)
This hook copies `wpa_supplicant`, `wpa_cli`, `iw`, `rfkill`, `ip`, wireless regulatory databases (`regulatory.db`), card firmwares, and the saved configuration into the initramfs image:
```sh
sudo tee /etc/initramfs-tools/hooks/nadamu_wifi > /dev/null << 'EOF'
#!/bin/sh
set -e

PREREQ=""
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

. /usr/share/initramfs-tools/hook-functions

# Copy wireless utilities and network binaries
for bin in wpa_supplicant wpa_cli rfkill iw ip; do
    bin_path=$(command -v "$bin" 2>/dev/null || true)
    if [ -n "$bin_path" ]; then
        copy_exec "$bin_path" "$bin_path"
    fi
done

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

# Copy wireless device firmware (Intel iwlwifi, Realtek, Atheros, MediaTek, etc.)
for fw in /lib/firmware/iwlwifi-* /lib/firmware/intel/iwlwifi/* /lib/firmware/rtw* /lib/firmware/ath10k/* /lib/firmware/ath11k/* /lib/firmware/mediatek/*; do
    if [ -f "$fw" ]; then
        mkdir -p "${DESTDIR}$(dirname "$fw")"
        cp -f "$fw" "${DESTDIR}$fw"
    fi
done

exit 0
EOF
sudo chmod +x /etc/initramfs-tools/hooks/nadamu_wifi
```

#### Step 4. Create Initramfs Premount Script (`nadamu_wifi_up`)
This script runs asynchronously at `init-premount` time: it instantly yields control to the boot process so the LUKS prompt appears with zero delay, while connecting to the hotspot and obtaining a DHCP lease in the background:
```sh
sudo tee /etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up > /dev/null << 'EOF'
#!/bin/sh
PREREQ="udev"
prereqs() { echo "$PREREQ"; }
case "$1" in prereqs) prereqs; exit 0;; esac

CONF="/etc/wpa_supplicant/wpa_supplicant.conf"
[ -f "$CONF" ] || exit 0

bg_wifi_connect() {
    rfkill unblock wifi 2>/dev/null || rfkill unblock all 2>/dev/null || true
    iw reg set RU 2>/dev/null || true

    WLAN_IF="wlan0"
    for ifpath in /sys/class/net/*; do
        if [ -d "$ifpath/wireless" ] || [ -d "$ifpath/phy80211" ]; then
            WLAN_IF=$(basename "$ifpath")
            break
        fi
    done

    ip link set "$WLAN_IF" up 2>/dev/null || true
    mkdir -p /run /var/run /run/wpa_supplicant

    wpa_supplicant -B -i "$WLAN_IF" -Dnl80211,wext -c "$CONF" -P /run/wpa_supplicant.pid 2>/dev/null || true

    for i in $(seq 1 20); do
        if iw dev "$WLAN_IF" link 2>/dev/null | grep -q "Connected to"; then
            udhcpc -i "$WLAN_IF" -n -q -t 5 2>/dev/null || true
            break
        fi
        sleep 1
    done
}

# Run in background for instantaneous LUKS prompt display
bg_wifi_connect >/dev/null 2>&1 &
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
