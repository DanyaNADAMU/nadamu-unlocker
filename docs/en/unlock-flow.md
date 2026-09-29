# Unlock Flow Contract (Android App ⇄ Laptop Initramfs)

[ English ](unlock-flow.md) • [ Русский ](../ru/unlock-flow.md)

> Single source of truth for the unlock protocol between the phone app
> (`android/`) and the laptop's initramfs scripts (`laptop/`, built into the
> initrd by the lab or installer). If you change anything here, you are
> changing BOTH sides — update this file in the same commit.
>
> Verified: 2026-09-29 (contract verified by lab test_unlock.py suite, Android TOFU host key pinning, structured logging, adaptive mapper polling, channel IP caching, strict trusted host filtering, and mandatory unlock confirmation)

## TL;DR

App connects via SSH (public-key only) to dropbear in the laptop's
initramfs, writes the LUKS passphrase into a well-known FIFO, then confirms
success by polling for the opened mapper device. Writing to the FIFO alone
is NOT success.

## Actors

| Side | Component | Role |
|---|---|---|
| Phone | `SshUnlocker` (SSHJ client) | connect, auth, write fifo, verify |
| Laptop | dropbear in initramfs | SSH server, pubkey-only |
| Laptop | cryptsetup in initramfs (`cryptroot`) | read fifo, run `cryptsetup open` |
| Laptop | `/bin/unlock` helper | same fifo write, for human interactive use |

## Connection

- Port: `22` (lab maps it to host port `2222`).
- Auth: SSH public key only. Dropbear runs with `-s -j -k`
  (no password, no port/host forwarding). Client key: Ed25519 preferred (with custom private key import support);
  the authorized_keys entry MUST be in OpenSSH wire format
  (`ssh-ed25519 AAAA... comment`), NOT X.509/SPKI base64. On Android, full BouncyCastle provider
  is registered at slot 1 to support X25519 key exchange and Ed25519 signatures.
- Host key pinning: TOFU (Trust On First Use) implemented with SHA-256 fingerprint verification and secure pinning in EncryptedSharedPreferences to prevent local MitM attacks on untrusted networks.
- Strict Host Filtering: Network discovery only reports and interacts with hosts whose host key fingerprint matches a user-pinned trusted laptop key. Untrusted devices are ignored silently.

## Delivery step

Write the raw passphrase (no trailing newline) into the first existing FIFO:

1. `/lib/cryptsetup/passfifo`
2. `/run/cryptsetup/passfifo`

Equivalent shell for debugging:

```sh
printf '%s' "$PASSPHRASE" > /lib/cryptsetup/passfifo
```

## Success criteria (authoritative)

Success = an opened LUKS mapper device exists in `/dev/mapper` (adaptive discovery excluding `control`, or specific target if configured):

```sh
MAPPERS=$(ls /dev/mapper 2>/dev/null | grep -v "^control$")
```

- The client MUST poll this (every 1 s, timeout ~15–30 s) after
  writing the fifo. Exit codes of the fifo write say nothing about the
  passphrase being correct.
- On wrong passphrase the watcher stays in its loop; the poll simply times
  out → report failure, allow retry.

## Handoff semantics (pivot to real system)

When the volume opens, the initramfs continues boot and the SSH session /
tunnels die. Clients MUST treat "connection dropped + mapper device was
present" as SUCCESS (handoff), not as an error.

## Known deviations (bugs)

None currently open against the unlock protocol contract. The previous deviations (passfifo success false positives, X.509 SPKI key encoding, dead password fallback, and hardcoded /24 scanning) have been resolved and covered with unit and lab tests.

## Security & Architecture Enhancements

- **Granular Event-Driven Triggers:** Independent triggers for USB connection, phone hotspot state, Wi-Fi / local network, and phone screen unlock.
- **Mandatory Unlock Confirmation:** Silent background password delivery is strictly prohibited. Background triggers present an interactive confirmation alert (`[Unlock Now]` / `[Dismiss]`). When biometric security is enabled, fingerprint confirmation is enforced for all unlock requests.
- **Strict Trusted Host Verification:** Both `Scan` and `Scan and Unlock All` strictly require matching pinned host key fingerprints; random network devices or unauthorized SSH instances are completely ignored.
- **Secure Non-Viewable Passphrase Vault:** LUKS passphrases are hardware-encrypted in Android KeyStore (AES-256-GCM) with screen capture prevention (`FLAG_SECURE`), non-viewable in UI (change/set only).
- **Host Key Pinning (TOFU):** Protects against local MitM on public and shared networks by prompting the user for SHA-256 fingerprint verification on first connect and strictly enforcing the pinned key on subsequent unlocks.
- **Structured Logging Subsystem:** 4-level logging (`DEBUG`, `INFO`, `WARN`, `ERROR`) with in-app filtering and one-click full export.
- **Fast Multi-Channel Discovery & IP Caching:** Instant cache hit checks across USB (RNDIS/NCM), Wi-Fi Hotspot, and LAN interfaces, with dynamic CIDR subnet scanning.

## Lab test mapping

`lab/test_unlock.py` implements the end-to-end verification suite:
1. Wait-for-SSH readiness probe.
2. Unauthorized SSH key rejection (untrusted key fails authentication).
3. Invalid passphrase rejection (passfifo delivery without opening `/dev/mapper/<target>`).
4. Valid passphrase delivery, client-side mapper poll verification (`/dev/mapper/test_crypt`), and VM handoff.
