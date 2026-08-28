# Branching Strategy & Release Workflow

[ English ](branching-and-releases.md) • [ Русский ](../ru/branching-and-releases.md)

> Guidelines for managing git branches, pull requests, automated CI checks,
> and publishing signed Android APK releases to GitHub Releases.
>
> Verified: 2026-08-28 — Release and CI workflows configured in `.github/workflows/`.

## TL;DR

Use **GitHub Flow**: branch off `main` (`feat/*`, `fix/*`, `docs/*`), open a
Pull Request, and merge after automated CI passes. Pull Requests run CI only
when relevant paths are touched. Releases are published automatically to
GitHub Releases whenever a version tag (`v*.*.*`) is pushed or manually
triggered via `workflow_dispatch`.

---

## 1. Branching Model (GitHub Flow)

```
        feat/new-ui  ───○───○───┐ (PR)
                                 │
main ───○───────────────────────●────────────────○ (v1.0.0 Release Tag)
         │                                       │
         └─── fix/ssh-timeout ──○───○────────────┘ (PR)
```

- **`main`**: The primary stable branch. Direct commits should be avoided.
  Code in `main` must always build cleanly and pass all unit/hygiene checks.
- **Feature & Fix branches**:
  - `feat/<short-description>`: New features (e.g. `feat/biometric-prompt`).
  - `fix/<short-description>`: Bug fixes (e.g. `fix/subnet-cidr-scan`).
  - `docs/<short-description>`: Documentation additions and updates.
  - `lab/<short-description>`: Lab environment or test fixture updates.

---

## 2. Pull Request & Push Workflow

### Step-by-Step

1. Update your local `main` branch:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Create and switch to a feature branch:
   ```sh
   git checkout -b feat/my-new-feature
   ```
3. Make changes, run local checks before pushing:
   ```sh
   # 1. Check documentation hygiene & contract rules
   python3 scripts/check_docs.py

   # 2. Run Android unit tests (if android/ is modified)
   cd android && ./gradlew test && cd ..
   ```
4. Commit your changes following conventional commit messages:
   ```sh
   git add <files>
   git commit -m "feat(android): add qr code scanner for identity keys"
   ```
5. Push to GitHub and open a Pull Request against `main`:
   ```sh
   git push -u origin feat/my-new-feature
   ```

### CI Path Filtering

CI runs only for components that have changed:
- **`docs-check.yml`**: Runs documentation linters and hygiene checks on all PRs.
- **`android-build.yml`**: Runs unit tests and builds a debug APK **only** when
  files in `android/**` or `.github/workflows/android-build.yml` change.
  Documentation, lab, or laptop changes do not trigger Android builds.

---

## 3. Release Process & APK Publishing

Releases are published to **GitHub Releases** along with:
1. The signed Release APK (`nadamu-unlocker-vX.Y.Z.apk`).
2. The SHA-256 checksum file (`nadamu-unlocker-vX.Y.Z.apk.sha256`).
3. Auto-generated release notes (changelog derived from merged PRs/commits).

### Triggering a Release via Git Tag

1. Ensure `main` is up to date:
   ```sh
   git checkout main
   git pull origin main
   ```
2. Create and push an annotated Semantic Version tag:
   ```sh
   git tag -a v1.0.0 -m "Release v1.0.0"
   git push origin v1.0.0
   ```
3. GitHub Actions workflow `.github/workflows/release.yml` will automatically
   build, sign, package, and publish the release.

### Manual Release via Web UI (`workflow_dispatch`)

1. Navigate to **Actions** → **Release Android APK** on GitHub.
2. Click **Run workflow**.
3. Enter the tag name (e.g. `v1.0.1`), select whether it is a draft or prerelease,
   and click **Run workflow**.
