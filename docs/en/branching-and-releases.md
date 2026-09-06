# Branching Strategy & Release Workflow

[ English ](branching-and-releases.md) • [ Русский ](../ru/branching-and-releases.md)

> Comprehensive guidelines for Git branching, opening Pull Requests, local code verification,
> automated CI/CD pipelines, and publishing signed Release APKs to GitHub Releases.
>
> Verified: 2026-08-28 — Release and CI workflows configured in `.github/workflows/`.

## TL;DR

Use **GitHub Flow**: create short-lived branches off `main` (`feat/*`, `fix/*`, `docs/*`), pass local tests and documentation linters, open a Pull Request, and merge after automated CI passes. Releases are automatically built, signed, and published to **GitHub Releases** whenever a version tag (`v*.*.*`) is pushed or manually triggered via `workflow_dispatch`.

---

## 1. Branching Model (GitHub Flow)

```
        feat/new-logging ───○───○───┐ (PR)
                                    │
main ───○───────────────────────────●────────────────○ (v1.1.0 Release Tag)
         │                                          │
         └─── fix/subnet-scan ──────○───○───────────┘ (PR)
```

- **`main`**: The primary stable branch. Direct commits to `main` should be avoided. Code in `main` must always build cleanly, pass all unit/hygiene checks, and be ready for release.
- **Development branches**:
  - `feat/<description>`: New features (e.g. `feat/structured-logging`, `feat/host-key-pinning`).
  - `fix/<description>`: Bug fixes (e.g. `fix/subnet-cidr-range`, `fix/passfifo-race`).
  - `docs/<description>`: Documentation additions, updates, and translations.
  - `lab/<description>`: Lab testbed or Docker/QEMU fixture updates.

---

## 2. Step-by-Step Development & Pull Request Workflow

### Step 1. Update `main` and create a feature branch
Before starting work, ensure your local `main` branch is in sync with `origin/main`:
```sh
git checkout main
git pull origin main
```
Create and switch to a dedicated topic branch:
```sh
git checkout -b feat/my-feature-name
```

### Step 2. Make changes and format commit messages
Follow the **Conventional Commits** format (in English):
- `feat(android): implement 4-level structured logging system`
- `fix(android): dynamic CIDR subnet scan without 254-host cap`
- `docs(laptop-setup): update Wi-Fi PMF and firmware initramfs instructions`
- `build(android): upgrade gradle dependencies`

Keep commits atomic: one logical step per commit.

### Step 3. Mandatory local pre-push checks
Always execute local verification commands before pushing:

```sh
# 1. Documentation linter & bilingual cross-link checks
python3 scripts/check_docs.py

# 2. Run Android unit tests
cd android && ./gradlew test && cd ..

# 3. Test local debug APK build
cd android && ./gradlew assembleDebug && cd ..
```

*Note: If you altered unlock protocols, network topology, or laptop setup, update corresponding documentation files in both `docs/en/` and `docs/ru/` in the same commit; otherwise `check_docs.py` will fail in CI.*

### Step 4. Push branch and open a Pull Request
Push your feature branch to GitHub:
```sh
git push -u origin feat/my-feature-name
```
Navigate to GitHub (`https://github.com/DanyaNADAMU/nadamu-unlocker`) and create a Pull Request against `main`.

---

## 3. Automated CI/CD & Pull Request Checks

GitHub Actions run automatically on every Pull Request:

- **`docs-check.yml`**: Verifies link hygiene, `Verified: YYYY-MM-DD` markers, and ensures code changes are accompanied by matching documentation updates.
- **`android-build.yml`**: Triggers when files inside `android/**` change. Executes unit tests on JDK 25 and builds a test debug APK.

### Inspecting PR build results:
1. At the bottom of the Pull Request page, find the **Checks** section.
2. Click **Details** next to any running or failed check to view full Gradle and linter console logs.

---

## 4. Release Process & APK Publishing

The release lifecycle is fully automated in `.github/workflows/release.yml`.

### Release Artifacts:
1. Signed and zipalign-optimized Release APK (`nadamu-unlocker-vX.Y.Z.apk`).
2. SHA-256 Checksum file (`nadamu-unlocker-vX.Y.Z.apk.sha256`).
3. Auto-generated changelog and release notes.

### Method 1. Automated Release via Git Tag (Primary)
1. Ensure `main` is up to date:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Create an annotated Semantic Version tag:
   ```sh
   git tag -a v1.1.0 -m "Release v1.1.0: structured logging, host key pinning, fast CIDR discovery"
   ```
3. Push the tag to GitHub:
   ```sh
   git push origin v1.1.0
   ```
4. GitHub Actions will automatically launch the `Release APK` workflow, build the signed binary, and publish the release.

### Method 2. Manual Release via Web UI (`workflow_dispatch`)
1. Go to **Actions** on GitHub (`https://github.com/DanyaNADAMU/nadamu-unlocker/actions`).
2. Select the **Release Android APK** workflow in the left sidebar.
3. Click **Run workflow**.
4. Enter the tag name (e.g. `v1.1.0`), optionally select *Draft* or *Prerelease*, and click **Run workflow**.

---

## 5. Downloading & Verifying the Release APK

Once the workflow succeeds:
1. Open the releases page: `https://github.com/DanyaNADAMU/nadamu-unlocker/releases`.
2. Under **Assets** of the latest release, download `nadamu-unlocker-vX.Y.Z.apk` to your Android device.
3. Verify file integrity using SHA-256:
   ```sh
   sha256sum nadamu-unlocker-vX.Y.Z.apk
   ```
   The checksum must match the value in the companion `.sha256` asset.
