# nadamu-unlocker

Universal and resilient remote LUKS disk unlocking system via Android app across multiple network topologies (USB modem, LAN/Ethernet, Wi-Fi, Reverse SSH WAN).

## Architecture

```
nadamu-unlocker/
├── android/            # Android companion application (Kotlin, Jetpack Compose, SSHJ)
├── laptop/             # initramfs hooks, unlock scripts, and configuration for Linux (Kali/Debian)
├── server/             # Reverse SSH server configurations and daemon scripts
├── test-lab/           # QEMU + LUKS headless testbed for safe initramfs debugging
└── .github/
    └── workflows/      # CI/CD pipelines (Android builds, linting, releases)
```

## Status
Initial project setup and architecture design.
