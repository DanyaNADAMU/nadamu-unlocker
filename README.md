# nadamu-unlocker

[ English ](README.md) • [ Русский ](README.ru.md) | [ 📖 Documentation ](docs/en/index.md)

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
[`docs/en/unlock-flow.md`](docs/en/unlock-flow.md) (Russian: [`docs/ru/unlock-flow.md`](docs/ru/unlock-flow.md)).

## Supported network modes

Full matrix with requirements and test coverage:
[`docs/en/network-modes.md`](docs/en/network-modes.md) (Russian: [`docs/ru/network-modes.md`](docs/ru/network-modes.md)).

| Mode | Topology | Status |
|---|---|---|
| A1 | Phone ⇄ laptop via USB tethering (RNDIS / CDC) | [implemented], lab-tested |
| A2 | Phone and laptop on same LAN, laptop via Ethernet | [implemented], lab-tested |
| A3 | Laptop on phone's Wi-Fi hotspot | [implemented], lab-tested |
| A4 | Phone and laptop on same Wi-Fi LAN | [planned] |
| B1 | Remote via VPS, reverse SSH tunnel from laptop | [planned] — next after A3 |
| B2 | Remote via VPS, WireGuard mesh | [planned], after B1 |

Verified: 2026-08-28 — A1, A2, and A3 flows exercised end-to-end by `lab/test_unlock.py`
and Android BouncyCastle X25519/Ed25519 provider registration.

## Repository layout

```
android/    Phone-side app (Kotlin, Jetpack Compose, SSHJ, BouncyCastle)
laptop/     Laptop-side installer: initramfs hooks + `unlock` CLI (Kali/Debian/Ubuntu)
lab/        QEMU test lab in Docker — builds a real initramfs, unlocks a real
            LUKS disk image end-to-end, no hardware needed
docs/       Full documentation (en/ and ru/), contracts, and ADRs
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
  [`docs/en/network-modes.md`](docs/en/network-modes.md).
- The Android client supports custom SSH keypair import and Ed25519 generation.

## Documentation Index

- 📖 [Documentation Portal (English)](docs/en/index.md) | [Портал документации (Русский)](docs/ru/index.md)
- 💻 [Laptop Target Setup Guide](docs/en/laptop-setup.md) | [Настройка ноутбука (RU)](docs/ru/laptop-setup.md)
- 🌐 [Network Modes Matrix](docs/en/network-modes.md) | [Матрица сетевых режимов (RU)](docs/ru/network-modes.md)
- 🔐 [Unlock Flow Contract](docs/en/unlock-flow.md) | [Протокол разблокировки (RU)](docs/ru/unlock-flow.md)
- 🚀 [Branching & Releases](docs/en/branching-and-releases.md) | [Ветвление и релизы (RU)](docs/ru/branching-and-releases.md)
