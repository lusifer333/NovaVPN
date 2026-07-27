# CI/CD Pipeline Specification

> Defines the continuous integration and delivery requirements for NovaVPN.

## 1. CI Workflow

**File:** `.github/workflows/android-build.yml`  
**Trigger:** Push to `main` or `develop`, PR to `main`

### 1.1 Jobs

| Job | Steps | Timeout |
|---|---|---|
| `Build Debug APK` | lint → test → assembleDebug → upload artifacts | 30 min |

### 1.2 Environment

| Requirement | Value |
|---|---|
| Runner | `ubuntu-latest` |
| JDK | Temurin 17 |
| Android SDK | Platform 34, Build Tools 34.0.0 |
| Gradle | 8.5 (via wrapper) |
| Cache | Gradle dependencies via `gradle/actions/setup-gradle@v3` |

### 1.3 Steps Detail

1. **Checkout** — `actions/checkout@v4`
2. **JDK Setup** — `actions/setup-java@v4` with Temurin 17
3. **Gradle Setup** — `gradle/actions/setup-gradle@v3` with caching
4. **Grant permissions** — `chmod +x gradlew`
5. **Lint** — `./gradlew lint --no-daemon`
6. **Test** — `./gradlew test --no-daemon`
7. **Assemble** — `./gradlew assembleDebug --no-daemon`
8. **Upload APK** — `actions/upload-artifact@v4`

### 1.4 Artifacts

| Artifact | Path | Required |
|---|---|---|
| `NovaVPN-debug` | `app/build/outputs/apk/debug/*.apk` | Yes |
| `lint-results` | `**/build/reports/lint*/` | On failure |
| `test-results` | `**/build/reports/tests/` | On failure |

## 2. Quality Gates

A PR may be merged only when:

- ✅ `lint` passes with zero errors
- ✅ `test` passes with zero failures
- ✅ `assembleDebug` produces an APK
- ⚠️ Lint warnings are reviewed (but non-blocking)

## 3. Release Workflow (Future)

A release should be triggered by a tag push (`v*`):

1. Build universal APK (AAB preferred for Play Store)
2. Sign with release keystore
3. Upload to GitHub Releases
4. (Optional) Upload to Google Play via Gradle Play Publisher

## 4. Current Status

| Check | Status | Notes |
|---|---|---|
| `lint` | ❌ Failing | Compilation errors (being fixed) |
| `test` | ⏳ Not run | Blocked by lint |
| `assembleDebug` | ⏳ Not run | Blocked by lint |

## 5. Cache Strategy

- Gradle wrapper distributions cached
- Gradle dependency cache (`.gradle/caches/`)
- Build cache is NOT currently used (performance trade-off)
- Read-only cache for PR builds, read-write for `main` branch
