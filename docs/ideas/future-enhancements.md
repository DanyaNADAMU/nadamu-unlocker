# Ideas & RFC Backlog: Unlocker

> Exploratory proposals, architectural hypotheses, and candidate feature requests for future iterations of Unlocker.
>
> Verified: 2026-08-28 — Initial ideas compilation.

## TL;DR

This document serves as an incubation space for prospective ideas, research items, and architectural proposals. Items here represent exploratory research and do not carry implementation commitments until formally promoted into `docs/plans/` and formalized via Architecture Decision Records (`docs/adr/`).

---

## Candidate RFCs & Concepts

### RFC-001: NFC Tap-to-Unlock
- **Concept**: Use Near-Field Communication (NFC) as an instantaneous physical proximity trigger.
- **Workflow**: The user taps their phone against an NFC sticker affixed to the laptop chassis. The Android system triggers the Unlocker foreground intent, prompts for biometric confirmation, and immediately dispatches the unlock payload over USB or Wi-Fi.
- **Pros**: Zero UI navigation required; physical proximity provides natural multi-factor proof of presence.
- **Cons**: Requires physical contact; laptop chassis materials (aluminum) may attenuate passive NFC tags.

### RFC-002: BLE (Bluetooth Low Energy) Beacon Early Wakeup
- **Concept**: Although ADR-0001 rejected running a full Bluetooth stack inside initramfs for data transport, a minimal BLE advertisement listener could signal to the phone that the laptop is in the boot stage.
- **Workflow**: Laptop firmware / early kernel emits a lightweight BLE advertisement beacon. The Android phone detects the beacon in the background and proactively presents a high-priority "Laptop Ready to Unlock" notification before the user even takes out their phone.
- **Pros**: Reduces perceived boot latency; automatic notification trigger without periodic Wi-Fi polling.
- **Cons**: Early-boot kernel Bluetooth driver dependencies and hardware firmware loading complexities.

### RFC-003: Dynamic Two-Factor Challenge via TOTP Keyslot
- **Concept**: Enhance standard LUKS password injection with a secondary time-based or counter-based one-time password.
- **Workflow**: The laptop initramfs cryptsetup configuration includes a secondary keyslot using `systemd-cryptsetup` or custom helper requiring both a static secret and a dynamic 6-digit TOTP code generated on the phone.
- **Pros**: Even if the static LUKS passphrase is eavesdropped or compromised, disk access remains protected without the rotating phone token.
- **Cons**: Clock drift in early initramfs before NTP synchronization; increased failure probability if phone and laptop RTC desynchronize.

### RFC-004: Wear OS Companion App
- **Concept**: Provide a Wear OS standalone tile and complication.
- **Workflow**: User taps complication on smartwatch. The watch instructs the phone (or communicates directly over local Wi-Fi if available) to trigger the unlock flow.
- **Pros**: Ultimate convenience for users wearing a smartwatch while docking their laptop.
- **Cons**: Additional platform codebase to maintain; biometric authentication delegates back to wrist lock status.

### RFC-005: Multi-Machine Fleet Management for Homelab / Headless Servers
- **Concept**: Extend the single-laptop model to manage multiple headless servers and homelab nodes.
- **Workflow**: The app UI maintains a fleet dashboard with status indicators for all configured targets. Group unlock allows broadcasting passphrases to multiple servers rebooted simultaneously following power outages.
- **Pros**: High utility for sysadmins and homelab enthusiasts running LUKS-encrypted Proxmox or Debian servers.
- **Cons**: Requires per-machine credential profiles and more sophisticated network routing.
