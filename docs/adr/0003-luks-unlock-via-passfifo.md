# ADR 0003: Unlock via cryptsetup passfifo

**Status:** Accepted (2026-08-24)

## TL;DR

The passphrase is delivered by writing it into the named pipe
`/lib/cryptsetup/passfifo` (fallback `/run/cryptsetup/passfifo`) over an
existing SSH session, instead of running `cryptsetup` directly from the SSH
command. The boot-side watcher script owns the actual
`cryptsetup open ...` call.

## Context

In the initramfs a watcher script (`scripts/local-top/nadamu_cryptroot`)
blocks waiting for the passphrase before the root filesystem can be mounted.
The phone-side app connects with SSHJ and must deliver the secret.

Options considered:

1. SSH exec runs `cryptsetup open` directly with the passphrase on stdin.
2. SSH exec writes the passphrase into a named FIFO; the boot-time watcher
   reads it and calls `cryptsetup`.

## Decision

Option 2 — passfifo. This matches how Debian's own `cryptroot` initramfs
scripts and tools like `dropbear-initramfs` expect remote unlocking to be
glued together, and keeps exactly one code path that invokes cryptsetup.

## Consequences

Positive:

- Single writer/reader ownership: only the local-top script touches
  cryptsetup; remote clients only produce bytes into a pipe. The protocol is
  trivial to reimplement on any client platform.
- The interactive `unlock` CLI (for humans over plain ssh) uses the same
  fifo, so one mechanism serves both the app and manual recovery.
- No passphrase in argv / process lists anywhere.

Negative / accepted risks:

- **Writing to the fifo succeeds even if the passphrase is wrong.** The
  watcher loops and waits again. Therefore "bytes delivered" MUST NOT be
  treated as "disk unlocked" — clients must confirm success separately
  (see `../en/unlock-flow.md`, Success criteria).
- FIFO paths differ between setups (`/lib/cryptsetup` vs `/run/cryptsetup`);
  clients must try both.
