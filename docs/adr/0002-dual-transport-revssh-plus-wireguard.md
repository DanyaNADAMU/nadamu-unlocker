# ADR 0002: Dual remote transport — reverse SSH tunnel plus WireGuard

**Status:** Accepted (2026-08-24)

## TL;DR

Remote unlock through a VPS (part B) will support both a reverse SSH tunnel
(B1) and WireGuard (B2), running simultaneously in the initramfs. B1 goes
first because it adds almost nothing to the image; B2 is the target
architecture for stability. The app tries transports in order:
local modes → WireGuard → reverse SSH.

## Context

When the phone and laptop are not on the same network, a VPS relays the
connection. Two viable designs were compared; mesh VPN services (Tailscale)
and custom NAT-traversal matchmaking were rejected as too heavy for an
initramfs (see Consequences).

Key constraints: minimal userland in initrd, any secret baked into the
unencrypted image is considered leaked, NAT between laptop and VPS must be
kept traversable for long periods while the machine sits at the boot prompt.

## Decision

1. **B1 — reverse SSH:** the laptop keeps an outbound
   `dbclient -R <port>:127.0.0.1:22 unlock@vps` connection alive with a
   supervision loop. `dbclient` is part of dropbear already shipped in the
   initramfs. The VPS-side key in authorized_keys MUST carry
   `restrict,port-forwarding,permitlisten="<port>"` so a stolen key grants
   port forwarding only, no shell.
2. **B2 — WireGuard:** the laptop brings up `wg0` from a config file
   (`ip link add wg0 type wireguard && wg setconf ...`); phone connects to
   the same VPS and reaches the laptop by its stable overlay IP. No scanner
   needed in this mode; the app stores device → overlay-IP bindings.
3. Both run concurrently in the initramfs; the app tries them in order.

## Consequences

Why two instead of one:

- Reverse SSH weaknesses: half-open tunnels after silent NAT timeout (needs
  a keepalive supervisor loop), availability windows during reconnects,
  unique remote port per device, key abuse prevention is purely
  server-config (easy to get wrong). Strength: plain TCP outbound passes
  almost every firewall, incl. networks that block UDP.
- WireGuard strengths: stateless daemon-less design, instant recovery,
  explicit liveness (`wg show` handshake age), structurally scoped secret
  (peer key cannot log into the VPS), stable device addresses.
  Weakness: UDP is blocked in some hostile networks — exactly where B1
  still works. Hence dual transport rather than replacement.

Rejected alternatives:

- **Tailscale / NetBird / ZeroTier:** heavy daemons (40+ MB binaries) and
  extra state in initramfs; great features we do not need for delivering
  one passphrase. Revisit only if a fleet of devices with ACLs appears.
- **Custom P2P matchmaking + hole punching:** unstable against symmetric
  NATs of mobile carriers; requires a relay fallback anyway, i.e. all the
  cost of B1/B2 plus a custom protocol.
- **OpenVPN / L2TP/IPsec:** X.509 PKI management plus clock dependency in
  early boot (cert validation fails at 1970), multi-daemon stacks
  (xl2tpd + strongswan + pppd). Disproportionate weight.

Operational notes:

- When the disk unlocks, the initramfs (and its tunnels) die — the app must
  treat "device disappeared from overlay/tunnel" together with mapper
  polling as success handoff, not error. The same channel should also be
  brought up in the real system by a unit, so pre/post-unlock behavior is
  uniform.
- Lab testing: a fake "VPS" service (sshd / wireguard container) runs in
  the same compose network, so part B is testable fully offline in CI.
