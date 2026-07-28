# Phase 2 — Real VPN Integration: Implementation Plan

> Do NOT start implementation until Phase 1 is fully signed off.

## Goal

Make NovaVPN actually connect to real VPN servers on an Android device.

## Tasks (ordered)

### T2.1 — Native Binary Download Strategy

**Problem:** Xray and Sing-box native binaries (~20MB each) cannot be committed to git.

**Solution:** Create a Gradle task that downloads prebuilt binaries during build:

```kotlin
// app/build.gradle.kts
val downloadEngines by tasks.registering {
    doLast {
        val dir = file("src/main/assets/engines")
        dir.mkdirs()
        // Download xray for arm64
        // Download sing-box for arm64
    }
}
tasks.named("preBuild") { dependsOn(downloadEngines) }
```

**Binary sources:**
- Xray: `https://github.com/XTLS/Xray-core/releases` — `Xray-linux-arm64-v8a.zip`
- Sing-box: `https://github.com/SagerNet/sing-box/releases` — `sing-box-*-linux-arm64.tar.gz`

**Files to create:**
- `buildscripts/download-engines.gradle.kts`
- `native/xray/arm64-v8a/` (extracted binary)
- `native/sing-box/arm64-v8a/` (extracted binary)

**Acceptance criteria:** `./gradlew assembleDebug` produces APK with native binaries in assets.

---

### T2.2 — Native Binary Loading in Engine Implementations

**Problem:** `XrayEngine` and `SingboxEngine` cannot find the native binary.

**Fix:** Update both engines to extract the binary from app assets to the app's private directory on first run.

```kotlin
// In XrayEngine.initialize(context):
val binaryDir = File(context.filesDir, "engines")
binaryDir.mkdirs()
val binaryFile = File(binaryDir, "xray")
context.assets.open("engines/xray").use { input ->
    binaryFile.outputStream().use { output ->
        input.copyTo(output)
    }
}
binaryFile.setExecutable(true)
```

**Files to change:**
- `engine/xray/.../XrayEngine.kt`
- `engine/singbox/.../SingboxEngine.kt`

**Acceptance criteria:** Engine binary is extracted and executable.

---

### T2.3 — Engine Process Configuration

**Problem:** `EngineContext` interface has `tunFileDescriptor: Int` but the engine needs to pass the TUN fd to the native process.

**Fix:** Update both engines to set the `--tun-fd` flag (or equivalent) for the native process.

**Xray:** Does not support TUN fd directly. Need to pass TUN configuration via the config file with `tun` section.

**Sing-box:** Supports TUN mode via `inbounds[0].tun`. Need to configure TUN inbound.

**Files to change:**
- `engine/api/.../EngineContext.kt`
- `engine/xray/.../XrayConfigParser.kt`
- `engine/singbox/.../SingboxConfigParser.kt`

---

### T2.4 — VPNService Lifecycle

**Problem:** `NovaVpnService.kt` starts the engine but doesn't properly handle all states.

**Fix:** Audit and fix:
1. `onStartCommand` — handle `ACTION_START` and `ACTION_STOP`
2. `onDestroy` — stop engine + close TUN fd
3. `onRevoke` — stop everything
4. Process death recovery — restart service

**Files to change:**
- `app/.../service/NovaVpnService.kt`

---

### T2.5 — Connection Flow End-to-End

**User action:**
```
Connect button
  → Select server
  → Prepare VPN service
  → Build TUN interface
  → Extract engine binary
  → Write engine config
  → Start engine process
  → Verify connectivity
  → Show connected state
```

**Files to change:**
- `app/.../service/NovaVpnService.kt`
- `feature/home/.../HomeViewModel.kt` (wire connect button)

---

### T2.6 — Disconnect Flow

**User action:**
```
Disconnect button
  → Send stop to engine process
  → Wait for process exit
  → Close TUN interface
  → Stop foreground service
  → Show disconnected state
```

---

### T2.7 — Additional Engine Tests

Write tests for:
- Engine binary extraction
- Engine start
- Engine stop
- Config generation for real server

**Files to create:**
- `engine/xray/src/test/.../XrayEngineTest.kt`
- `engine/singbox/src/test/.../SingboxEngineTest.kt`

---

## Files To Create

| File | Purpose |
|---|---|
| `buildscripts/download-engines.gradle.kts` | Gradle task to download native binaries |
| `scripts/download_engines.sh` | Shell script for manual download |

## Files To Modify

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add engine download task |
| `engine/api/.../EngineContext.kt` | Add tun configuration details |
| `engine/xray/.../XrayEngine.kt` | Binary loading + process TUN config |
| `engine/xray/.../XrayConfigParser.kt` | TUN inbound configuration |
| `engine/singbox/.../SingboxEngine.kt` | Binary loading + process TUN config |
| `engine/singbox/.../SingboxConfigParser.kt` | TUN inbound configuration |
| `app/.../service/NovaVpnService.kt` | Lifecycle audit |
| `feature/home/.../HomeViewModel.kt` | Wire connect button |

## Risks

| Risk | Mitigation |
|---|---|
| Xray doesn't support TUN directly | Use tun2socket as intermediary, or use sing-box which has native TUN |
| Binary size too large for APK | Only include arm64 for now; split APKs per ABI |
| Engine process crashes silently | Add stdout/stderr capture + log forwarding |
| VPNService killed by system | Use `START_STICKY` + high-priority foreground notification |
