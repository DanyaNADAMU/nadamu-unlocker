# Unlock flow contract (Android app ⇄ laptop initramfs)

> Single source of truth for the unlock protocol between the phone app
> (`android/`) and the laptop's initramfs scripts (`laptop/`, built into the
> initrd by the lab or installer). If you change anything here, you are
> changing BOTH sides — update this file in the same commit.
>
> Verified: never (contract extracted from code, not yet enforced by tests)

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
  (no password, no port/host forwarding). Client key: Ed25519 preferred;
  the authorized_keys entry MUST be in OpenSSH wire format
  (`ssh-ed25519 AAAA... comment`), NOT X.509/SPKI base64.
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

## Known deviations (bugs) — fix, don't rely on them

These are current-code behaviors that VIOLATE this contract:

1. `SshUnlocker` returns Success right after the fifo write, without the
   mapper poll → false positives on wrong passwords.
2. `KeyManager` generates an EC P-256 key and stores the public part as
   X.509/SPKI base64 labeled `ecdsa-sha2-nistp256` — dropbear will reject
   it. Must convert to SSH wire encoding (or generate via sshj).
3. `SshUnlocker` falls back to password auth `root/root`, which can never
   succeed (`-s` disables passwords) and must not exist.
4. `NetworkScanner` hardcodes /24 scanning instead of using the interface's
   real prefix length.

## Lab test mapping

`lab/test_unlock.py` implements: wait-for-SSH probe → fifo write →
(watcher auto-verifies and powers off the VM). It currently does not model
the client-side mapper polling because the lab VM shuts down on success;
client-side verification will be tested once implemented in the app.
