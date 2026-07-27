# Missing Features

> Features not yet implemented but required for MVP completeness.

## F-01: Launcher Icon

**Priority:** P1  
**Description:** App needs a proper launcher icon instead of default Android icon.  
**Acceptance criteria:** App displays custom icon in app drawer.

---

## F-02: Native Engine Binaries

**Priority:** P0  
**Description:** `XrayEngine` and `SingboxEngine` need native binaries in `app/src/main/assets/` or `jniLibs/`.  
**Acceptance criteria:** Engines can find and execute their native binary.  
**Files to create:**
- `native/xray/arm64-v8a/xray`
- `native/sing-box/arm64-v8a/sing-box`

---

## F-03: VMess Link Import from Clipboard

**Priority:** P1  
**Description:** Users should be able to import a single VMess/VLESS/Trojan link from clipboard.  
**Acceptance criteria:** Paste a link → parsed and added as a server.

---

## F-04: QR Code Scanner

**Priority:** P2  
**Description:** Camera-based QR code scanning for subscription URLs.  
**Dependencies:** CameraX or ML Kit.  
**Acceptance criteria:** Scan QR → subscription added.

---

## F-05: Auto-Update Subscriptions

**Priority:** P2  
**Description:** Subscriptions should auto-refresh on a schedule using WorkManager.  
**Acceptance criteria:** Configurable interval, background update, notification on changes.

---

## F-06: App Language Selector

**Priority:** P2  
**Description:** Settings should allow language selection (English, Persian, etc.).  
**Acceptance criteria:** Change language → UI strings update without restart.

---

## F-07: Per-App VPN Configuration UI

**Priority:** P2  
**Description:** Allow users to select which apps use the VPN tunnel.  
**Acceptance criteria:** App list with checkboxes → only selected apps go through VPN.
