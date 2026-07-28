# NovaVPN Release Process

## Versioning Schema

Versions follow **Semantic Versioning** (`MAJOR.MINOR.PATCH`):

| Component | Meaning | Example |
|---|---|---|
| MAJOR | Breaking architecture changes | 1.0.0 → 2.0.0 |
| MINOR | New features, backward compatible | 1.0.0 → 1.1.0 |
| PATCH | Bug fixes, small improvements | 1.0.0 → 1.0.1 |

`versionCode` is auto-computed: `MAJOR * 10000 + MINOR * 100 + PATCH`

Current version: **0.1.0** (pre-release)

## Pre-Release vs Stable

| | Pre-Release | Stable |
|---|---|---|
| Tag format | `v0.x.x` | `v1.0.0` and above |
| GitHub pre-release flag | ✅ true | ❌ false |
| Intended audience | Testers, developers | End users |
| Phase | Phase 2 in progress | Production ready |
| MinSdk | 26 | 26 |

## How to Create a Test Release

### Prerequisites

- Push access to `lusifer333/NovaVPN`
- GitHub Actions enabled on the repository

### Steps

1. **Determine the next version**

   Check [releases](https://github.com/lusifer333/NovaVPN/releases) for the latest tag.
   Increment accordingly:
   - Bug fix: PATCH (e.g. 0.1.0 → 0.1.1)
   - New feature: MINOR (e.g. 0.1.0 → 0.2.0)
   - Breaking change: MAJOR (e.g. 0.1.0 → 1.0.0)

2. **Create and push a tag**

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. **Wait for CI**

   GitHub Actions will:
   - Build the debug APK
   - Create a GitHub Release
   - Upload the APK as a release asset
   - Mark the release as pre-release

   Monitor progress at:
   https://github.com/lusifer333/NovaVPN/actions

4. **Verify the release**

   Go to https://github.com/lusifer333/NovaVPN/releases
   The new release should appear with the APK attached.

## How to Download and Install

1. Go to https://github.com/lusifer333/NovaVPN/releases
2. Find the desired release
3. Under "Assets", click the `.apk` file to download
4. On your Android device:
   - Enable "Install from unknown apps" for your browser/file manager
   - Open the downloaded APK
   - Follow the installation prompts
   - Grant VPN permission when requested by the app
5. Add a subscription URL and connect

## Manual Build (without GitHub Actions)

```bash
# Build debug APK with custom version
./gradlew assembleDebug \
  -PversionName=0.1.0 \
  -PversionCode=1

# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Release Workflow

The CI workflow is defined in `.github/workflows/release.yml`.

**Trigger:** Push a tag matching `v*` (e.g. `v0.1.0`, `v1.0.0`)

**What it does:**
1. Extracts version from tag (`v0.1.0` → `0.1.0`)
2. Computes `versionCode` from semver
3. Builds debug APK with those versions
4. Creates a GitHub Release
5. Uploads APK as release asset
6. Marks the release as pre-release (until Phase 2 completes)
