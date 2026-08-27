# lab/ — QEMU test lab

> Builds a real Kali/Debian initramfs with dropbear + LUKS test disk inside
> a container, boots it in QEMU, and unlocks it end-to-end. No hardware or
> GUI needed; this is where initramfs changes get tested before touching a
> real laptop.

Verified: 2026-08-27 (A1 flow and contract test suite verified in QEMU lab)

## TL;DR

`compose.yml` builds/boots everything; `run_tests.sh` proves the unlock;
`ssh_lab.sh` gives you a shell in the VM's initramfs. Rebuild triggers are
files you drop into `data/`.

## Quick start

```sh
cd lab
docker compose up -d     # first run builds assets (several minutes)
./run_tests.sh           # automated e2e unlock test
./ssh_lab.sh             # manual interactive shell into VM initramfs
tail -f data/console.log # VM serial console mirror (readable without tty)
```

Requirements: Docker or Podman, `/dev/kvm` for acceleration (falls back to
slow TCG emulation otherwise), port `2222/tcp` free on the host.

## Files

| File | Purpose |
|---|---|
| `Containerfile` | Lab image: Kali base + qemu, cryptsetup, dropbear-initramfs |
| `entrypoint.sh` | Builds assets if missing, supervisor loop restarting QEMU runs |
| `build_lab.sh` | Creates LUKS disk image, Ed25519 keys, initramfs hooks, runs `update-initramfs` |
| `run_qemu.sh` | Boots kernel+initrd+disk with user-mode networking, hostfwd tcp/2222→22 |
| `test_unlock.py` | Full test suite: SSH probe, auth rejection, wrong pass rejection, passfifo write, mapper polling |
| `run_tests.sh` | Wrapper invoking `test_unlock.py` |
| `ssh_lab.sh` | Interactive SSH using the lab's generated key |

## Rebuild triggers

The entrypoint supervisor loop watches for marker files in `data/`:

- `touch data/cmd.rebuild` — force initramfs/disk rebuild, keep keys.
- `touch data/cmd.rekey` — rotate the Ed25519 keypair, bake new pubkey into
  initramfs, rebuild.

## How the pieces fit

1. `build_lab.sh` generates keys + 500 MB LUKS2 disk (`data/test_disk.img`),
   writes initramfs hooks (dropbear config, authorized_keys, passfifo,
   cryptroot watcher) into the container's `/etc/initramfs-tools/`, then
   produces `data/test_initrd.img` via `update-initramfs`.
2. QEMU boots it with `-netdev user,hostfwd=tcp::22-:22`; compose maps host
   `2222` → container `22`.
3. The `local-top` watcher waits on `/lib/cryptsetup/passfifo`; on valid
   passphrase it opens `/dev/mapper/test_crypt` and powers the VM off —
   that poweroff IS the current success signal (see
   `../docs/unlock-flow.md` for the contract the app must follow).

## Planned

- Fake "VPS" service (sshd / wireguard containers) in the same compose
  network to test modes B1/B2 offline — see
  `../docs/network-modes.md`.
