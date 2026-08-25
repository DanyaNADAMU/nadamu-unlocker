# nadamu-unlocker

> Unlock a LUKS-encrypted laptop from your Android phone, over the network.
> The laptop runs an SSH server inside its initramfs (before the disk is
> decrypted); the phone sends the passphrase to it.

## How it works

```
┌─────────┐   SSH + passphrase    ┌──────────────────────────────┐
│ Android │ ────────────────────► │ Laptop: Dropbear in initramfs│
│  app    │                       │ → writes /lib/cryptsetup/    │
└─────────┘                       │   passfifo → LUKS opens      │
                                  └──────────────────────────────┘
```

The protocol contract between both sides is documented in
[`docs/unlock-flow.md`](docs/unlock-flow.md).

## Supported network modes

Full matrix with requirements and test coverage:
[`docs/network-modes.md`](docs/network-modes.md).

| Mode | Topology | Status |
|---|---|---|
| A1 | Phone ⇄ laptop via USB tethering (RNDIS) | [implemented], lab-tested |
| A2 | Phone and laptop on same LAN, laptop via Ethernet | **[in progress]** |
| A3 | Laptop on phone's Wi-Fi hotspot | [planned] |
| A4 | Phone and laptop on same Wi-Fi LAN | [planned] |
| B1 | Remote via VPS, reverse SSH tunnel from laptop | [planned] — next after A2 |
| B2 | Remote via VPS, WireGuard mesh | [planned], after B1 |

## Repository layout

```
android/    Phone-side app (Kotlin, Jetpack Compose, SSHJ)
laptop/     Laptop-side installer: initramfs hooks + `unlock` CLI (Kali/Debian)
lab/        QEMU test lab in Docker — builds a real initramfs, unlocks a real
            LUKS disk image end-to-end, no hardware needed
docs/       Contracts, architecture decision records, network mode matrix
```

## Quick start (lab)

Requires Docker or Podman with `/dev/kvm` for acceleration.

```sh
cd lab
docker compose up -d      # builds initramfs + LUKS disk, boots QEMU VM
./run_tests.sh            # automated end-to-end unlock test
./ssh_lab.sh              # manual shell into the VM's initramfs
```

## Security model

- The passphrase travels over SSH only; dropbear in initramfs runs with
  password authentication disabled (`-s -j -k`), public key auth only.
- The initramfs is unencrypted by nature: any secret baked into it
  (Wi-Fi PSK, tunnel keys) must be scoped so its leak does not compromise
  the disk or other machines. Per-mode secrets are listed in
  [`docs/network-modes.md`](docs/network-modes.md).
- Host key pinning in the app is planned; see AGENTS.md "Known deviations".

## Docs index

See [`AGENTS.md`](AGENTS.md) for the full documentation map and the rules
this repository follows for keeping docs current.
