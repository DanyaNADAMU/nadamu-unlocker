# android/ — phone-side app

> Kotlin Android app that finds the laptop running dropbear in its
> initramfs and sends the LUKS passphrase over SSH.

Verified: never (build verified only in CI)

## TL;DR

Jetpack Compose UI, SSHJ for SSH, multi-interface subnet scanner for discovery.
Talks the protocol documented in `../docs/en/unlock-flow.md` (Russian: `../docs/ru/unlock-flow.md`). Supports local modes
A1 (USB tethering), A2 (LAN / Ethernet), and A3 (Wi-Fi hotspot).

## Build & Test

JDK 25 (Amazon Corretto) and Gradle 9.7.1.

```sh
cd android
./gradlew test              # Run unit tests (KeyManagerTest, NetworkScannerTest, SshUnlockerTest)
./gradlew assembleDebug     # APK: app/build/outputs/apk/debug/
./gradlew assembleRelease   # Release APK: app/build/outputs/apk/release/
```

CI runs unit tests and builds the debug APK on every push/PR to main
(`.github/workflows/android-build.yml`). Tagged releases trigger automated
signing and publication to GitHub Releases
(`.github/workflows/release.yml`; see `../docs/en/branching-and-releases.md` / `../docs/ru/branching-and-releases.md`).

## Code map

```
app/src/
├── main/java/mu/nada/unlocker/
│   ├── MainActivity.kt      # Compose UI: password field, scan button, unlock button, console log
│   ├── data/
│   │   ├── KeyManager.kt    # Ed25519 identity key generation (OpenSSH wire format) + passphrase storage
│   │   └── NetworkScanner.kt# CIDR prefix calculation + TCP probe for dropbear banner
│   └── ssh/
│       └── SshUnlocker.kt   # SSHJ client: pubkey auth, write passfifo, poll /dev/mapper/<target>
└── test/java/mu/nada/unlocker/
    ├── data/
    │   ├── KeyManagerTest.kt
    │   └── NetworkScannerTest.kt
    └── ssh/
        └── SshUnlockerTest.kt
```

## Component-specific TODOs

- Private key lives in plaintext SharedPreferences; migrate to
  Android Keystore / EncryptedSharedPreferences later (deps already present:
  `androidx.security:security-crypto`).
- Host key pinning (TOFU) not implemented — `PromiscuousVerifier` accepts
  anything, MITM within the same network is possible until fixed.
