# android/ — phone-side app

> Kotlin Android app that finds the laptop running dropbear in its
> initramfs and sends the LUKS passphrase over SSH.

Verified: never (build verified only in CI)

## TL;DR

Jetpack Compose UI, SSHJ for SSH, subnet scanner for discovery. Talks the
protocol documented in `../docs/unlock-flow.md`. Currently targets mode A1;
mode A2 work includes fixing the known protocol deviations listed there.

## Build

JDK 17 required.

```sh
cd android
gradle assembleDebug        # APK: app/build/outputs/apk/debug/
```

CI builds this on every push/PR to main
(`.github/workflows/android-build.yml`) and uploads the debug APK as an
artifact.

## Code map

```
app/src/main/java/mu/nada/unlocker/
├── MainActivity.kt      # Compose UI: password field, scan button, unlock button, console log
├── data/
│   ├── KeyManager.kt    # client identity key + saved passphrase storage (SharedPreferences)
│   └── NetworkScanner.kt# interface enumeration + /24 TCP-probe scan for dropbear banner
└── ssh/
    └── SshUnlocker.kt   # SSHJ client: connect, write passfifo, report result
```

## Component-specific TODOs

See "Known deviations" in `../docs/unlock-flow.md` — all four are in this
directory. Additionally:

- Private key lives in plaintext SharedPreferences; migrate to
  Android Keystore / EncryptedSharedPreferences later (deps already present:
  `androidx.security:security-crypto`).
- Host key pinning (TOFU) not implemented — `PromiscuousVerifier` accepts
  anything, MITM within the same network is possible until fixed.
