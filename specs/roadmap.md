# NovaVPN — Development Roadmap

> Version 1.0.0
> Last updated: Phase 1 complete

## Phase 1: Build Stability ✅ COMPLETED

**Goal:** Make CI pass reliably. Fix all compilation and lint errors.

| Task | Status | Priority |
|---|---|---|
| Fix lint errors in CI | ✅ Fixed | P0 |
| Fix test errors | ✅ Pass | P0 |
| Build clean APK | ✅ CI passes | P0 |
| Verify Gradle configuration | ✅ Fixed | P1 |
| Fix KSP + Hilt annotation processing | ✅ Fixed (Hilt 2.51, direct registration) | P1 |
| Kotlin 2.0 Migration | ✅ Applied | P0 |
| Compose Compiler Plugin Migration | ✅ Applied | P0 |
| Android 14 `foregroundServiceType` | ✅ Fixed | P1 |
| Launcher icon resources | ✅ Added | P1 |

**Final toolchain versions:**

| Component | Version |
|---|---|
| Gradle | 8.5 |
| AGP | 8.2.2 |
| Kotlin | **2.0.0** |
| KSP | **2.0.0-1.0.24** |
| Compose Compiler | auto (via `kotlin.plugin.compose`) |
| Compose BOM | 2024.02.00 |
| Hilt | **2.51** |
| Room | 2.6.1 |
| kotlinx-serialization | **1.6.3** |

---

## Phase 2: Real VPN Integration (NEXT)

**Goal:** NovaVPN actually connects to VPN servers on a real Android device.

| Task | Status | Priority |
|---|---|---|
| Bundle Xray native binary (ARM64) | ⏳ Pending | P0 |
| Bundle Sing-box native binary (ARM64) | ⏳ Pending | P0 |
| Verify VPNService tunnel setup | ⏳ Pending | P0 |
| Test start/stop lifecycle | ⏳ Pending | P1 |
| Test engine process management | ⏳ Pending | P1 |
| Handle Android 14 foreground service restrictions | ✅ Done | P1 |
| Real VLESS connection test | ⏳ Pending | P1 |

**Definition of done:** VPN connects to a real server with confirmed internet access.

---

## Phase 3: Real Device Testing (Future)

**Goal:** Validate behavior on real hardware.

| Task | Status | Priority |
|---|---|---|
| Install debug APK on device | ⏳ Pending | P0 |
| Test subscription URL parsing | ⏳ Pending | P1 |
| Test connect/disconnect 50+ times | ⏳ Pending | P1 |
| Test battery consumption | ⏳ Pending | P2 |

**Definition of done:** 50+ connect/disconnect cycles without crash.

---

## Phase 4: Advanced Testing (Future)

**Goal:** Smart Test system is fully operational.

**Definition of done:** Auto Connect selects and connects to best server.

---

## Phase 5: Desktop Preparation (Future)

**Goal:** Prepare codebase for future desktop platforms.

**Definition of done:** Core domain module compiles on JVM without Android SDK.

---

## Phase 6: Desktop Apps (Future)

**Goal:** Windows, Linux, macOS clients.
