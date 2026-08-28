# ADR 0004: Android Toolchain and Device Compatibility Strategy

**Status:** Accepted (2026-08-27)

## TL;DR

The Android client builds with Amazon Corretto 25 (JDK 25 LTS), Gradle 9.7+,
and Kotlin 2.x with the modern Compose compiler Gradle plugin, while targeting
a minimum runtime of Android 8.0 Oreo (`minSdk = 26`) using D8/R8 bytecode
desugaring to maximize end-user device reach.

## Context

The phone application must run reliably on as many real-world Android devices
as possible while maintaining strict security, hardware-backed cryptography,
and high developer ergonomics.

We needed to establish:
1. Build environment JDK and build system compatibility.
2. Minimum supported Android OS runtime (`minSdk`) vs compile/target SDK.
3. Bytecode specification and desugaring mechanism.

Options considered:
- **`minSdk < 26` (Android 7 or older):** Lacks consistent hardware-backed
  Android Keystore guarantees, modern NIO / `NetworkCapabilities` APIs, and
  standardized Ed25519 support.
- **`minSdk = 26` (Android 8.0 Oreo):** Covers virtually all active Android
  devices in circulation, provides mature `java.time`, modern networking APIs,
  and robust hardware Keystore integration.
- **Legacy JDK 17 vs JDK 25 LTS:** JDK 25 LTS (Amazon Corretto) paired with
  Gradle 9.7+ provides long-term stability and modern toolchain support.

## Decision

1. **Build Toolchain:** Use Amazon Corretto 25 (JDK 25 LTS) and Gradle 9.7.1
   wrapper for builds and CI.
2. **Kotlin & Compose:** Kotlin 2.x using `org.jetbrains.kotlin.plugin.compose`
   and AGP 8.8+ / 9.x.
3. **Runtime & OS Compatibility:**
   - `minSdk = 26` (Android 8.0 Oreo)
   - `compileSdk = 35` / `targetSdk = 35` (Android 15)
   - `sourceCompatibility` / `targetCompatibility = JavaVersion.VERSION_17`
     with D8 desugaring enabled.

## Consequences

Positive:
- Maximum device coverage for end-users across all Android releases from
  Android 8.0 through Android 15+.
- Consistent hardware-backed security, cryptographic primitives, and
  multi-interface networking capabilities.
- Modern build speeds and LTS support with Java 25 and Gradle 9.7+.

Negative / accepted risks:
- Requires JDK 25 for local developer build environments or CI runners.
