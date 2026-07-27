# Future Improvements

> Features and improvements for after MVP stabilization.

## I-01: Speed-Improved CI

**Priority:** P2  
**Description:** Use Gradle build caching and parallel execution for faster CI runs.  
**Acceptance criteria:** Full CI run completes under 10 minutes.

---

## I-02: Dependency Guard

**Priority:** P2  
**Description:** Add a Gradle dependency guard plugin to prevent accidental dependency leaks.  
**Acceptance criteria:** CI fails if a forbidden dependency is added.

---

## I-03: API-Based Subscription Validation

**Priority:** P2  
**Description:** When adding a subscription URL, validate it's reachable and parseable before saving.  
**Acceptance criteria:** Invalid URLs show descriptive error messages.

---

## I-04: Connection History Charts

**Priority:** P2  
**Description:** Statistics screen shows visual charts of connection history, latency trends, and score changes.  
**Acceptance criteria:** Compose Canvas or third-party chart library renders historical data.

---

## I-05: Quick Settings Tile

**Priority:** P2  
**Description:** Android Quick Settings tile for one-tap connect/disconnect.  
**Acceptance criteria:** Tile toggles VPN on/off without opening app.

---

## I-06: Export/Import Settings

**Priority:** P3  
**Description:** Users can export/import all subscriptions and settings as a JSON file.  
**Acceptance criteria:** Export creates file → Import restores all data.

---

## I-07: Widget Support

**Priority:** P3  
**Description:** Android home screen widget showing connection status.  
**Acceptance criteria:** Widget updates when connection state changes.

---

## I-08: Desktop UI (Phase 6)

**Priority:** P3  
**Description:** Compose Multiplatform desktop UI for Linux and Windows.  
**Acceptance criteria:** Basic connect/disconnect and server list on desktop.
