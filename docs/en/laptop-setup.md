# Laptop Target Setup Guide

[ English ](laptop-setup.md) • [ Русский ](../ru/laptop-setup.md)

> Complete manual and automated setup guide for Kali / Debian / Ubuntu laptops.
> Configures `dropbear-initramfs`, required kernel modules, and network services for remote LUKS unlocking.
>
> Verified: 2026-08-28 (contract verified by lab test_unlock.py suite and QEMU multi-transport harness)

---

## 1. Architecture: The Early Boot Lifecycle

When an encrypted computer boots up or resumes from hibernation:

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
               │          ──► Configures IP via DHCP (IP=dhcp / udhcpc)
               │          ──► Starts Dropbear SSH server on Port 22 (pubkey auth only)
               │          ──► Creates named pipe: /lib/cryptsetup/passfifo
               │          ──► cryptsetup waits for passphrase on passfifo or console
               │
               ▼  ◄── Phone connects via SSH, writes passphrase into passfifo
[ LUKS Volume Unlocked ]  ──► /dev/mapper/<target> appears
               │
               ▼
     [ Pivot to Real OS ] ──► Initramfs network & Dropbear terminate cleanly
               │          ──► Encrypted root is mounted, systemd boots desktop
               ▼
    [ Desktop / User Session ]
```

---

## 2. Required Packages

| Package | Purpose in Initramfs |
|---|---|
| `dropbear-initramfs` | Lightweight SSH server built for the early-boot RAM disk. |
| `cryptsetup-initramfs` | Initramfs hooks for LUKS disk decryption and passfifo creation. |
| `busybox` | Minimal UNIX utility suite (`sh`, `udhcpc`, `ip`, `printf`, `test`). |
| `wpasupplicant` | WPA/WPA2 client for Wi-Fi association in initramfs (Modes A3/A4). |
| `rfkill` | Utility to unblock wireless hardware/software blocks. |
| `iw` | CLI tool to query and configure wireless devices. |

To install all dependencies manually:
```sh
sudo apt-get update
sudo apt-get install -y dropbear-initramfs cryptsetup-initramfs busybox wpasupplicant rfkill iw
```

---

## 3. Local Network Modes (A1, A2, A3, A4) Detailed Setup

### Mode A1: USB Tethering (Phone ⇄ USB Cable ⇄ Laptop)

**Topology:** Connect the phone with a USB cable and enable "USB Tethering" on Android. The phone acts as a DHCP server and router for the laptop.

1. **Add USB Networking Kernel Modules:**
   Edit `/etc/initramfs-tools/modules` and append:
   ```
   rndis_host
   cdc_ether
   cdc_ncm
   usbnet
   ```
   *Explanation:* `rndis_host` provides Android RNDIS driver support; `cdc_ether` and `cdc_ncm` support standard CDC USB ethernet devices.

2. **Enable Network & Dropbear in Initramfs:**
   Edit `/etc/initramfs-tools/initramfs.conf`:
   ```conf
   DROPBEAR=y
   IP=dhcp
   ```

3. **Configure Dropbear Security (Public Key Auth Only):**
   Edit `/etc/dropbear-initramfs/config` (or `/etc/dropbear/initramfs/dropbear.conf`):
   ```conf
   DROPBEAR_OPTIONS="-p 22 -s -j -k -E"
   ```
   *Explanation of flags:*
   - `-p 22`: Listen on TCP port 22.
   - `-s`: Disable password authentication (forces public-key authorization only).
   - `-j`: Disable local port forwarding (prevents tunneling through initramfs).
   - `-k`: Disable remote port forwarding.
   - `-E`: Output error log to stderr/system log.

4. **Install Client Public Key:**
   Copy the public key displayed in the Nadamu Android app into:
   `/etc/dropbear/initramfs/authorized_keys` (or `/etc/dropbear-initramfs/authorized_keys`).
   Set permissions:
   ```sh
   sudo chmod 600 /etc/dropbear/initramfs/authorized_keys
   ```

5. **Rebuild Initramfs:**
   ```sh
   sudo update-initramfs -u -k all
   ```
   *Explanation of flags:*
   - `-u`: Update an existing initramfs image.
   - `-k all`: Apply changes across all installed Linux kernel versions.

---

### Mode A2: Ethernet LAN (Laptop on Wired Network)

**Topology:** Laptop is connected to the same local router/switch via Ethernet cable as the phone (via Wi-Fi on the same subnet).

1. **Ensure Ethernet Driver is in Initramfs:**
   Identify your Ethernet NIC module (e.g. `lspci -k` or `lsmod | grep -E "r8169|e1000e|tg3|igb"`).
   Add your NIC driver name (e.g., `r8169`, `e1000e`, `virtio_net`) to `/etc/initramfs-tools/modules`.

2. **Configuration:**
   Same `IP=dhcp`, Dropbear options (`-p 22 -s -j -k -E`), and `authorized_keys` as in Mode A1.
   Rebuild with `sudo update-initramfs -u -k all`.

---

### Mode A3: Wi-Fi Hotspot (Laptop Joins Phone's Hotspot)

**Topology:** Turn on the Wi-Fi Hotspot on your phone. When the laptop boots, initramfs activates the Wi-Fi card, joins your phone's hotspot, gets an IP, and starts Dropbear.

1. **Kernel Modules:**
   Add wireless subsystem modules to `/etc/initramfs-tools/modules`:
   ```
   cfg80211
   mac80211
   rfkill
   ```
   *(Also ensure your Wi-Fi driver module, e.g. `iwlwifi`, `ath9k`, `rtw88`, is present or built-in).*

2. **Save Hotspot Credentials:**
   Create `/etc/nadamu/wifi/wpa_supplicant.conf`:
   ```conf
   ctrl_interface=/run/wpa_supplicant
   update_config=1

   network={
       ssid="YOUR_PHONE_HOTSPOT_SSID"
       psk="YOUR_PHONE_HOTSPOT_PASSWORD"
       key_mgmt=WPA-PSK
   }
   ```
   Set secure permissions:
   ```sh
   sudo chmod 600 /etc/nadamu/wifi/wpa_supplicant.conf
   ```

3. **Initramfs Hook (`/etc/initramfs-tools/hooks/nadamu_wifi`):**
   Copies `wpa_supplicant`, `iw`, `rfkill`, regulatory database, and `wpa_supplicant.conf` into the initramfs image.

4. **Initramfs Premount Script (`/etc/initramfs-tools/scripts/init-premount/nadamu_wifi_up`):**
   Unblocks wireless devices with `rfkill unblock wifi`, brings up the interface, connects via `wpa_supplicant`:
   ```sh
   wpa_supplicant -B -i "$WLAN_IF" -c /etc/wpa_supplicant/wpa_supplicant.conf -P /run/wpa_supplicant.pid
   ```
   *Explanation of flags:*
   - `-B`: Run in daemon / background mode.
   - `-i <iface>`: Specify wireless network interface (e.g., `wlan0`).
   - `-c <path>`: Path to configuration file.
   - `-P <pidfile>`: Save process ID to file.

   Then acquires DHCP address via BusyBox `udhcpc`:
   ```sh
   udhcpc -i "$WLAN_IF" -n -q -t 5
   ```
   *Explanation of flags:*
   - `-i <iface>`: Target interface.
   - `-n`: Exit immediately if lease is not obtained (non-blocking).
   - `-q`: Quit immediately after obtaining a valid DHCP lease.
   - `-t 5`: Send up to 5 DHCP discover packets.

5. **Rebuild Initramfs:**
   ```sh
   sudo update-initramfs -u -k all
   ```

---

### Mode A4: Same Home/Office Wi-Fi Network

**Topology:** Both phone and laptop connect to your existing home or office Wi-Fi router.

- Setup is identical to Mode A3, with `YOUR_HOME_WIFI_SSID` and `YOUR_HOME_WIFI_PASSWORD` specified in `/etc/nadamu/wifi/wpa_supplicant.conf`.
- **Important Router Requirement:** AP/Client Isolation (Guest Network Isolation) must be **disabled** on your Wi-Fi router, otherwise the router will prevent wireless clients from communicating with each other on port 22.

---

## 4. Automated Installation Script

To perform all above steps automatically, run:

```sh
sudo ./install.sh
```

Or pass Wi-Fi credentials directly for Mode A3/A4:
```sh
sudo ./install.sh --ssid "MyPhoneHotspot" --psk "SecretPass123"
```

After running `install.sh`:
1. Open **Nadamu Unlocker** on your Android phone.
2. Copy the **Client SSH Public Key** displayed in the app.
3. Paste it into `/etc/dropbear/initramfs/authorized_keys` on your laptop.
4. Run `sudo update-initramfs -u -k all`.
