# ADR 0001: Wi-Fi, not Bluetooth, as the wireless transport in initramfs

**Status:** Accepted (2026-08-24)

## TL;DR

For wireless unlock (modes A3/A4) we use Wi-Fi only. Bluetooth would require
a full BlueZ stack and interactive pairing inside the initramfs — months of
work for a worse result. Wi-Fi reuses the existing TCP/IP + SSH stack via
wpa_supplicant + DHCP, which initramfs-tools already supports.

## Context

The laptop's initramfs must expose the Dropbear SSH server over some
wireless link when no cable is available. Candidate transports: Wi-Fi,
Bluetooth (PAN or RFCOMM).

Constraints:

- The initramfs is minimal: no systemd, limited RAM, often no valid system
  clock.
- The whole unlock stack is TCP/IP + SSH (SSHJ client on Android, dropbear
  in initramfs).
- The phone can act as a Wi-Fi hotspot, so "no router" scenarios must still
  work without infrastructure.

## Decision

Use Wi-Fi (client mode, wpa_supplicant + udhcpc) for A3/A4.

## Consequences

Positive:

- Zero changes to the SSH-based unlock stack; Wi-Fi is just another link
  layer under it.
- `wpasupplicant` ships an initramfs hook on Debian/Kali; busybox provides
  `udhcpc`. Only firmware blobs for the laptop's Wi-Fi chip are extra.
- Phone hotspot covers the "no router in the field" scenario.

Negative / accepted risks:

- Wi-Fi PSK lives in the unencrypted initramfs. Leak impact is limited to
  joining that network; dropbear accepts public-key auth only (`-s -j -k`),
  so network access alone does not unlock the disk. Use a dedicated SSID /
  per-device PSK where possible.
- Firmware for the laptop's Wi-Fi chip must be present in the initrd;
  per-device configuration may be needed.
- Association + DHCP add ~3–7 s before the laptop is reachable.
- AP/client isolation on some routers silently breaks modes A2/A4 — this is
  a documented troubleshooting step, not something we can fix in code.

Rejected alternatives:

- **Bluetooth:** needs kernel modules + BlueZ userland in initramfs,
  interactive pairing with baked-in link keys, and non-public Android PAN
  APIs; ~10 m range; months of work.
