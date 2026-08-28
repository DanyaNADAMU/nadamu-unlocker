# Unlock Flow Contract (Android App ⇄ Laptop Initramfs)

[ English ](unlock-flow.md) • [ Русский ](../ru/unlock-flow.md)

> Single source of truth for the unlock protocol between the phone app
> (`android/`) and the laptop's initramfs scripts (`laptop/`, built into the
> initrd by the lab or installer). If you change anything here, you are
> changing BOTH sides — update this file in the same commit.
>
> Verified: 2026-08-28 (contract verified by lab test_unlock.py suite, Android BouncyCastle X25519/Ed25519 provider registration, and local multi-interface tethering)

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
| Laptop | `scripts/local-top/nadamu_cryptroot` | read fifo, run `cryptsetup open` |
| Laptop | `/bin/unlock` helper | same fifo write, for human interactive use |

## Connection

- Port: `22` (lab maps it to host port `2222`).
- Auth: SSH public key only. Dropbear runs with `-s -j -k`
  (no password, no port/host forwarding). Client key: Ed25519 preferred (with custom private key import support);
  the authorized_keys entry MUST be in OpenSSH wire format
  (`ssh-ed25519 AAAA... comment`), NOT X.509/SPKI base64. On Android, full BouncyCastle provider
  is registered at slot 1 to support X25519 key exchange and Ed25519 signatures.
- Host key pinning: planned TOFU (first-connect trust, then pinned).
  Not implemented yet.

## Delivery step

Write the raw passphrase (no trailing newline) into the first existing FIFO:

1. `/lib/cryptsetup/passfifo`
2. `/run/cryptsetup/passfifo`

Equivalent shell for debugging:

```sh
printf '%s' "$PASSPHRASE" > /lib/cryptsetup/passfifo
```

## Success criteria (authoritative)

Success = the mapper device exists after delivery:

```sh
ls /dev/mapper/<target>
```

- The client MUST poll this (suggested: every 1 s, timeout ~30 s) after
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

Planned security enhancements:
- Host key pinning (TOFU) to protect against local MITM on shared networks.
- Android Keystore / EncryptedSharedPreferences migration for client private key storage.

## Lab test mapping

`lab/test_unlock.py` implements the end-to-end verification suite:
1. Wait-for-SSH readiness probe.
2. Unauthorized SSH key rejection (untrusted key fails authentication).
3. Invalid passphrase rejection (passfifo delivery without opening `/dev/mapper/<target>`).
4. Valid passphrase delivery, client-side mapper poll verification (`/dev/mapper/test_crypt`), and VM handoff.
