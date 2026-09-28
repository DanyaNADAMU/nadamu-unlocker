# AGENTS.md

Instructions for AI agents (and humans) working in this repository.

## What this project is

**unlocker** unlocks a LUKS-encrypted laptop from an Android phone.
The laptop runs a Dropbear SSH server inside its *initramfs*; the phone
connects over the network and injects the disk passphrase into
`/lib/cryptsetup/passfifo` via SSH.

Key constraint that shapes everything: code that runs at boot lives in the
**unencrypted initramfs image**, so it is minimal, must not depend on system
services, and any secret baked into it is considered readable by anyone with
the laptop.

## Repository map

```
android/    Android app (Kotlin, Compose, SSHJ). Discovers laptop, sends passphrase.
laptop/     What gets installed on the target laptop: initramfs hooks + unlock CLI.
lab/        QEMU-based test lab in Docker: builds a real initramfs and tests unlocking end-to-end.
docs/       Architecture specifications, execution plans, ADRs, and multilingual guides.
.github/    CI/CD: Android debug APK build, release publishing, doc linter.
```

## Documentation map — read only what you need

| Question | Document |
|---|---|
| What is the high-level architecture, component breakdown, and security boundaries? | `docs/architecture/overview.md` |
| What are the current execution milestones and roadmap checklists? | `docs/plans/roadmap.md` |
| What future features and RFC proposals are in the backlog? | `docs/ideas/future-enhancements.md` |
| How do the app and initramfs talk? Protocol details | `docs/en/unlock-flow.md` (RU: `docs/ru/unlock-flow.md`) |
| How are branches, PRs, and releases managed? | `docs/en/branching-and-releases.md` (RU: `docs/ru/branching-and-releases.md`) |
| Which network topologies are supported / planned? | `docs/en/network-modes.md` (RU: `docs/ru/network-modes.md`) |
| How to configure the target laptop? | `docs/en/laptop-setup.md` (RU: `docs/ru/laptop-setup.md`) |
| Why Wi-Fi and not Bluetooth in initramfs? | `docs/adr/0001-wifi-not-bluetooth-in-initramfs.md` |
| Why both reverse SSH and WireGuard for remote unlock? | `docs/adr/0002-dual-transport-revssh-plus-wireguard.md` |
| Why passfifo instead of other unlock mechanisms? | `docs/adr/0003-luks-unlock-via-passfifo.md` |
| What toolchain and Android OS versions are supported? | `docs/adr/0004-android-toolchain-and-device-compatibility.md` |
| How to build/run/test one component? | `README.md` inside that component's directory |

## Commands

```sh
# Lab (QEMU + LUKS end-to-end test), requires podman or docker with /dev/kvm
cd lab && docker compose up -d          # build lab assets + start VM container
cd lab && ./run_tests.sh                # run automated unlock test against running VM
cd lab && ./ssh_lab.sh                  # interactive shell into the VM's initramfs

# Android build (JDK 25 Corretto, Gradle 9.7.1)
cd android && ./gradlew assembleDebug   # CI does the same; output in app/build/outputs/apk/
```

## Conventions

- Docs are in **English**. Keep them English even if conversation is not.
- Every doc starts with a 2–5 line TL;DR. One document answers one question.
- Facts live in exactly **one** place; everywhere else links to it.
- Status markers: `[implemented]`, `[planned]`, `[broken]`,
  `Verified: YYYY-MM-DD` (date the claim was last confirmed by a test).
- Shell snippets must be copy-pasteable from repo root unless noted otherwise.

## Doc maintenance rules (mandatory)

Update documentation **in the same change** as the code:

1. Changed anything about the unlock protocol (passfifo paths, success
   criteria, key formats, ports) → update `docs/en/unlock-flow.md` and `docs/ru/unlock-flow.md`.
2. Changed network topology support or added a mode → update
   `docs/en/network-modes.md` and `docs/ru/network-modes.md` matrix rows.
3. Changed laptop setup or initramfs hooks → update
   `docs/en/laptop-setup.md` and `docs/ru/laptop-setup.md`.
4. Made an architectural choice where alternatives were rejected → add
   `docs/adr/NNNN-short-name.md`. ADRs are immutable; supersede, don't edit.
5. Changed build/run/test steps of a component → update that component's
   `README.md`.
6. If you could not verify a documented claim by running it, mark it
   `Verified: never` instead of leaving it unmarked.

These rules are **enforced** by `scripts/check_docs.py`
(`.github/workflows/docs-check.yml` runs it on every PR): if trigger code
paths change without their docs in the same diff, CI fails. If a code change
genuinely has no doc impact, add `[docs-ok]` to one commit message to bypass.
Run locally before pushing:

```sh
python3 scripts/check_docs.py                        # hygiene only (links, Verified)
python3 scripts/check_docs.py --base origin/main     # + trigger rules vs main
```

## Known deviations from docs / current bugs

Keep this list honest; remove entries when fixed.

- None currently active against the unlock contract. Protocol deviations (mapper-poll verification, OpenSSH wire key format, SSH pubkey authentication, CIDR subnet calculation) are resolved and covered by unit and lab test suites.
