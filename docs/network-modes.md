# Network modes matrix

> All supported and planned ways the phone reaches the laptop's initramfs.
> Status here mirrors reality; update rows in the same change that adds or
> fixes a mode. Protocol details live in `docs/unlock-flow.md`.

## TL;DR

Local modes A1–A4 (phone and laptop near each other), remote modes B1–B2
(through a VPS). A1 works; A2 is in progress; B1 is next. Wi-Fi modes share
one implementation (ADR 0001); B1/B2 run concurrently (ADR 0002).

## Local modes

| Mode | Topology | Laptop needs in initrd | Phone needs | Secrets baked into initrd | Status |
|---|---|---|---|---|---|
| A1 | USB tethering: phone ⇄ cable ⇄ laptop (RNDIS/NCM) | usbnet drivers, DHCP on usb0 (already default) | subnet scan port 22 → SSH → passfifo | none | **[implemented]**, lab-tested via QEMU user-net |
| A2 | Same LAN, laptop on Ethernet | nothing new — DHCP on eth0 | same scan against LAN subnet | none | **[in progress]** |
| A3 | Laptop joins phone's Wi-Fi hotspot | wpa_supplicant + firmware for laptop's Wi-Fi chip + DHCP on wlan0 | connect to hotspot, then same scan | Wi-Fi PSK (scoped: join-network only) | [planned] — shares implementation with A4 |
| A4 | Both on same Wi-Fi LAN | same as A3 but SSID = home router | same scan on home subnet | Wi-Fi PSK | [planned] — same code as A3 |

Verified: 2026-08-25 — A1 verified end-to-end in the QEMU lab
(`lab/test_unlock.py`: SSH probe → fifo write → unlock → poweroff).
A2 claims are not yet machine-verified.

Notes:

- A3/A4 are one implementation (wpa_supplicant hook + config); they differ
  only in which AP the laptop associates with. See
  `docs/adr/0001-wifi-not-bluetooth-in-initramfs.md`.
- Multi-NIC race: when both Ethernet and Wi-Fi are up in initramfs,
  interface selection must be explicit (`ip=...` cmdline or DEVICE pinning),
  otherwise DHCP may configure the wrong link.

## Remote modes (via VPS)

| Mode | Path | Laptop needs | Phone needs | Secrets in initrd | Status |
|---|---|---|---|---|---|
| B1 | phone → VPS:2222 ← reverse tunnel ← laptop | `dbclient -R` supervision loop | plain SSH to VPS port | VPS private key, restricted server-side to port-forwarding only (`restrict,port-forwarding,permitlisten`) | [planned] — next after A2 |
| B2 | phone ⇄ WireGuard ⇄ VPS ⇄ WireGuard ⇄ laptop | `wg` setup script + config | official WireGuard app (split tunnel) + normal SSH to overlay IP | WG peer private key (overlay-scoped only) | [planned] after B1 |

Notes:

- B1 and B2 will run simultaneously; the app tries transports in order:
  local modes → WireGuard → reverse SSH. Rationale:
  `docs/adr/0002-dual-transport-revssh-plus-wireguard.md`.
- In B2 the app stores stable device → overlay-IP bindings; no scanning.
- Tunnel death at pivot (initramfs exits into real system) is part of the
  success handoff semantics, see `docs/unlock-flow.md`.
- Lab plan: a fake "VPS" service (sshd / wireguard container) in the lab
  compose network so B modes are tested offline end-to-end.

## Troubleshooting checklist (modes A2/A4)

If the scanner finds nothing while everything "looks connected":

1. Router AP/client isolation (a.k.a. guest network isolation) — phone can
   reach the internet but not the laptop. Most common silent failure.
2. Laptop actually got DHCP lease? Check initramfs console log
   (`ip addr`, `cat /proc/net/fib_trie`).
3. Wrong subnet assumption by the scanner — verify prefix length handling.
