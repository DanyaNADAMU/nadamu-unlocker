# Nadamu Unlocker Documentation

[ English ](index.md) • [ Русский ](../ru/index.md)

> Secure, automated network unlocking of LUKS-encrypted laptops from an Android smartphone.
>
> Verified: 2026-08-28 (contract verified by lab test_unlock.py suite and Android client release)

---

## Welcome to Nadamu Unlocker

Nadamu Unlocker allows you to boot your LUKS-encrypted laptop without typing the password on the physical keyboard. Your phone connects to the laptop's early-boot `initramfs` environment via SSH over USB cable (tethering), local Wi-Fi, Ethernet, or remote VPS tunnels, and safely delivers the passphrase to the LUKS decryption subsystem.

---

## Documentation Navigation

| Section | Description |
|---|---|
| 💻 **[Laptop Target Setup](laptop-setup.md)** | Step-by-step instructions for Kali / Debian / Ubuntu laptops: packages, kernel modules, Dropbear flags, and Wi-Fi hooks. |
| 🌐 **[Network Modes Matrix](network-modes.md)** | Overview of all supported connection topologies (USB Tethering, LAN, Wi-Fi Hotspot, Wi-Fi LAN, VPS Reverse SSH, WireGuard). |
| 🔐 **[Unlock Protocol Contract](unlock-flow.md)** | Technical specification of the Android app ⇄ Dropbear initramfs interaction, `/lib/cryptsetup/passfifo` injection, and verification polling. |
| 🚀 **[Branching & Releases](branching-and-releases.md)** | Git workflow, automated CI/CD checks, APK signing, and GitHub Releases. |

---

## Quick Architecture Overview

```
 ┌────────────────┐              SSH (Port 22)             ┌─────────────────────────────┐
 │ Android Device │ ──────────────────────────────────────►│ Laptop (Early Boot Initramfs)│
 │                │  Pubkey Auth (Ed25519) + Passphrase   │                             │
 │ • Network scan │                                        │ • Dropbear SSH Daemon       │
 │ • Custom keys  │                                        │ • /lib/cryptsetup/passfifo  │
 └────────────────┘                                        │ • /dev/mapper/<volume>      │
                                                           └──────────────┬──────────────┘
                                                                          │ (Unlocked)
                                                                          ▼
                                                           ┌─────────────────────────────┐
                                                           │ Real Root OS / Desktop Boot │
                                                           └─────────────────────────────┘
```
