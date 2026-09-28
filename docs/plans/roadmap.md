# Project Roadmap & Execution Plan: Unlocker

> Implementation status, development milestones, and technical checklist for Unlocker local and remote unlock topologies.
>
> Verified: 2026-08-28 — Milestone 1 & 2 fully implemented and verified via unit tests and automated QEMU lab harness.

## TL;DR

This document tracks completed and planned deliverables for Unlocker across all phases. Phase 1 (Local network modes A1, A2, A3) and Phase 2 (Android biometric security, host key pinning, Quick Settings Tile) are completed and lab-verified. Phase 3 (Mode A4 Wi-Fi LAN), Phase 4 (Mode B1 VPS Reverse SSH), and Phase 5 (Mode B2 WireGuard mesh) constitute the current forward roadmap.

---

## Milestone Breakdown & Feature Checklist

### Phase 1: Local Network Modes (A1, A2, A3) [implemented]
- [x] **Mode A1 (USB Tethering)**:
  - [x] Kernel gadget/RNDIS driver auto-loading in initramfs (`cdc_ether`, `rndis_host`).
  - [x] Fixed subnet fallback (`192.168.42.0/24`) and dynamic gateway discovery.
  - [x] End-to-end unlock validation in automated test suite.
- [x] **Mode A2 (Ethernet LAN)**:
  - [x] DHCP client support in early initramfs (`ipconfig` / `dhclient`).
  - [x] Subnet sweep discovery and target IP resolution.
- [x] **Mode A3 (Phone Hotspot)**:
  - [x] `wpa_supplicant` microcode and driver integration in initramfs.
  - [x] Wi-Fi scan and automatic association with phone AP.
  - [x] Passfifo injection via hotspot local subnet (`192.168.43.0/24`).

### Phase 2: Android Client Security & Polish [implemented]
- [x] **Hardware-Backed Cryptography**:
  - [x] Android KeyStore AES-256-GCM encryption for stored LUKS passphrases.
  - [x] Biometric authentication enforcement (`BiometricPrompt`) prior to secret decryption.
- [x] **SSH & Key Management**:
  - [x] SSHJ integration with BouncyCastle provider for X25519 key exchange and Ed25519 host keys.
  - [x] Custom SSH keypair generation (Ed25519) and OpenSSH public key export.
  - [x] Strict host key pinning with user confirmation on unknown/changed fingerprints.
- [x] **System Integration**:
  - [x] Background monitoring service with sticky foreground notification (`UnlockForegroundService`).
  - [x] Quick Settings Tile (`UnlockTileService`) for one-tap unlock from the notification panel.
  - [x] Dynamic CIDR subnet scanning without arbitrary host caps.

### Phase 3: Home & Office Wi-Fi LAN (Mode A4) [planned]
- [ ] Multi-SSID configuration in laptop initramfs hook (`/etc/initramfs-tools/hooks/wpa_supplicant`).
- [ ] Safe roaming between home/office networks during early boot.
- [ ] mDNS / Zeroconf advertising in initramfs (e.g. `laptop-unlock.local`) to eliminate IP scanning.
- [ ] Lab test harness simulation for multi-AP Wi-Fi environments.

### Phase 4: Remote VPS Relay via Reverse SSH (Mode B1) [planned]
- [ ] Dropbear / autossh client hook in initramfs establishing outbound reverse tunnel to public VPS.
- [ ] Relay access control: phone connects to VPS port, which routes to laptop initramfs Dropbear.
- [ ] VPS server setup automation and hardened SSH configuration guidelines.
- [ ] Failure recovery: timeout backoff if VPS is unreachable, falling back to local keyboard prompt.

### Phase 5: Remote VPS Mesh via WireGuard (Mode B2) [planned]
- [ ] WireGuard in-kernel module and `wg` tool bundling in initramfs.
- [ ] Point-to-point WireGuard handshake over cellular/Wi-Fi to VPS coordinator.
- [ ] Direct peer-to-peer connection establishment between phone WireGuard and laptop WireGuard.
- [ ] Security boundary validation: ensuring WireGuard private key rotation does not compromise disk security.
