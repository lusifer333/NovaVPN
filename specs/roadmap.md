# NovaVPN — Development Roadmap

> Version 1.0.0

## Phase 1: Build Stability (Current)

**Goal:** Make CI pass reliably. Fix all compilation and lint errors.

| Task | Status | Priority |
|---|---|---|
| Fix lint errors in CI | 🔴 Failing | P0 |
| Fix test errors | ⏳ Pending | P0 |
| Build clean APK | ⏳ Pending | P0 |
| Verify Gradle configuration | ⏳ Pending | P1 |
| Fix KSP + Hilt annotation processing | ⏳ Pending | P1 |

**Definition of done:** `./gradlew clean lint test assembleDebug` passes on CI.

---

## Phase 2: Real VPN Integration

**Goal:** NovaVPN actually connects to VPN servers on a real Android device.

| Task | Status | Priority |
|---|---|---|
| Bundle Xray native binary (ARM64) | ⏳ Pending | P0 |
| Bundle Sing-box native binary (ARM64) | ⏳ Pending | P0 |
| Verify VPNService tunnel setup | ⏳ Pending | P0 |
| Test foreground service behavior | ⏳ Pending | P1 |
| Test start/stop lifecycle | ⏳ Pending | P1 |
| Test engine process management | ⏳ Pending | P1 |
| Handle Android 14 foreground service restrictions | ⏳ Pending | P1 |
| Handle configuration changes | ⏳ Pending | P2 |

**Definition of done:** VPN connects to a real server with confirmed internet access.

---

## Phase 3: Real Device Testing

**Goal:** Validate behavior on real hardware.

| Task | Status | Priority |
|---|---|---|
| Install debug APK on device | ⏳ Pending | P0 |
| Test subscription URL parsing | ⏳ Pending | P1 |
| Test server selection | ⏳ Pending | P1 |
| Test connect/disconnect 50+ times | ⏳ Pending | P1 |
| Test reconnect on network change | ⏳ Pending | P1 |
| Test battery consumption | ⏳ Pending | P2 |
| Test background behavior | ⏳ Pending | P2 |
| Test boot receiver | ⏳ Pending | P2 |

**Definition of done:** 50+ connect/disconnect cycles without crash.

---

## Phase 4: Advanced Testing

**Goal:** Smart Test system is fully operational.

| Task | Status | Priority |
|---|---|---|
| Implement Fast Test (TCP + TLS + latency) | ⏳ Pending | P1 |
| Implement Deep Test (full tunnel) | ⏳ Pending | P1 |
| Test ranking algorithm | ⏳ Pending | P1 |
| Auto-switch on server failure | ⏳ Pending | P2 |
| UI feedback during testing | ⏳ Pending | P2 |

**Definition of done:** Auto Connect selects and connects to best server.

---

## Phase 5: Desktop Preparation

**Goal:** Prepare codebase for future desktop platforms.

| Task | Status | Priority |
|---|---|---|
| Extract shared business logic to KMP module | ⏳ Pending | P2 |
| Create platform abstraction interfaces | ⏳ Pending | P2 |
| Separate Android-specific engine code | ⏳ Pending | P2 |
| Evaluate Compose Multiplatform for UI | ⏳ Pending | P3 |

**Definition of done:** Core domain module compiles on JVM without Android SDK.

---

## Phase 6: Desktop Apps (Future)

**Goal:** Windows, Linux, macOS clients.

| Platform | Status | Priority |
|---|---|---|
| Linux (GTK or Compose Desktop) | 📅 Future | P3 |
| Windows | 📅 Future | P3 |
| macOS | 📅 Future | P3 |
