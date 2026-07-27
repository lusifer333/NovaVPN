# NovaVPN — Product Specification

> Version 1.0.0  
> Product: Lightweight modern VPN client

## 1. Product Vision

NovaVPN is a lightweight, modern, extensible VPN client built with clean architecture principles.  
It provides a simple user experience with smart server selection, support for multiple VPN engines, and robust subscription management.

**Primary platform:** Android  
**Future platforms:** Linux, Windows

## 2. Feature Specifications

### 2.1 VPN Engines

| ID | Feature | Priority | Status |
|---|---|---|---|
| F-ENG-01 | Engine abstraction layer | P0 | ✅ Implemented |
| F-ENG-02 | Xray Core engine | P0 | ✅ Implemented |
| F-ENG-03 | Sing-box engine | P0 | ✅ Implemented |
| F-ENG-04 | Engine switching in settings | P1 | ✅ Implemented |
| F-ENG-05 | Native binary bundling | P1 | ⏳ Pending |
| F-ENG-06 | Engine health monitoring | P2 | ⏳ Pending |

**Requirements:**
- `Engine` interface in `:engine:api` module
- Implementations in `:engine:xray` and `:engine:singbox`
- `EngineManager` for runtime engine selection
- Config parsers generate native JSON for each engine

### 2.2 Subscription Management

| ID | Feature | Priority | Status |
|---|---|---|---|
| F-SUB-01 | Add subscription URL | P0 | ✅ Implemented |
| F-SUB-02 | Delete subscription | P0 | ✅ Implemented |
| F-SUB-03 | Edit subscription (name, URL) | P1 | ✅ Implemented |
| F-SUB-04 | Enable/disable subscription | P1 | ✅ Implemented |
| F-SUB-05 | Manual refresh | P1 | ✅ Implemented |
| F-SUB-06 | Auto-update (scheduled) | P2 | ⏳ Pending |
| F-SUB-07 | Import from clipboard | P1 | ⏳ Pending |
| F-SUB-08 | Import from QR code | P2 | ⏳ Pending |
| F-SUB-09 | Import from file | P2 | ⏳ Pending |
| F-SUB-10 | Subscription export | P2 | ⏳ Pending |

**Supported formats:**
- `vmess://` (base64 JSON)
- `vless://` (UUID + config)
- `trojan://` (password + host)
- `ss://` (Shadowsocks)
- SIP008 (JSON format)
- Base64-encoded subscription bundles

### 2.3 Config Protocols

| Protocol | Support | Status |
|---|---|---|
| VMess | All transports (TCP, WS, gRPC, QUIC, HTTP) | ✅ Implemented |
| VLESS | TCP, WS, XTLS, Reality | ✅ Implemented |
| Trojan | TCP, WS, gRPC | ✅ Implemented |
| Shadowsocks | AEAD, simple | ✅ Implemented |
| SOCKS5 | Proxy | ✅ Parsed |

### 2.4 Server Testing

| ID | Feature | Priority | Status |
|---|---|---|---|
| F-TST-01 | Engine process verification | P0 | ✅ Implemented |
| F-TST-02 | Real HTTP connectivity check | P0 | ✅ Implemented |
| F-TST-03 | DNS resolution test | P0 | ✅ Implemented |
| F-TST-04 | Latency measurement | P0 | ✅ Implemented |
| F-TST-05 | Speed sample (download) | P1 | ✅ Implemented |
| F-TST-06 | Fast test (TCP + TLS + latency) | P1 | ⏳ Pending |
| F-TST-07 | Deep test (full tunnel) | P1 | ⏳ Pending |

**Smart Test approach:**
- Fast test: TCP handshake + TLS + latency (all candidates)
- Deep test: Start tunnel + DNS + HTTP + speed (top candidates only)
- A server is healthy only when real connectivity is verified

### 2.5 Smart Score

| Parameter | Weight | Description |
|---|---|---|
| Connection success rate | 30% | Historical connection reliability |
| Average latency | 20% | Lower is better (capped at 5000ms) |
| Recent success rate | 20% | Last 10 attempts ratio |
| Stability | 10% | Reconnects/disconnects penalty |
| DNS success | 10% | DNS resolution reliability |
| Speed | 10% | Download speed sample |

**Score range:** 0.0 — 100.0  
**Auto-connect** selects the highest-scoring server.

### 2.6 Auto Connect

| Trigger | Behavior |
|---|---|
| App start | Connect to best server automatically |
| Phone boot | Scheduled via WorkManager |
| Internet reconnect | Detect connectivity change |
| VPN disconnect | Try reconnection or switch server |
| Server failure | Auto-switch to next healthy server |

### 2.7 Settings

| Setting | Type | Status |
|---|---|---|
| VPN Engine selector | Enum (Xray, Sing-box) | ✅ Implemented |
| Custom DNS | Text field | ✅ Implemented |
| IPv6 toggle | Switch | ✅ Implemented |
| FakeDNS toggle | Switch | ✅ Implemented |
| Per-App VPN | Switch | ✅ Implemented |
| Auto Connect toggle | Switch | ✅ Implemented |
| Always-On VPN | Switch | ✅ Implemented |
| Notifications toggle | Switch | ✅ Implemented |
| Theme selector | System, Light, Dark | ✅ Implemented |
| Language selector | Dropdown | ⏳ Pending |

### 2.8 Logging

| Feature | Status |
|---|---|
| In-memory log buffer (1000 entries) | ✅ Implemented |
| Persistent log storage (Room) | ✅ Implemented |
| Filter by level (Debug, Info, Warning, Error) | ✅ Implemented |
| Search logs | ✅ Implemented |
| Copy logs to clipboard | ✅ Implemented |
| Export logs | ✅ Implemented |
| Clear logs | ✅ Implemented |

## 3. UI Screens

| Screen | Purpose | Status |
|---|---|---|
| Home | Connection status, connect/disconnect, stats | ✅ Implemented |
| Subscriptions | Add/edit/delete subscription URLs | ✅ Implemented |
| Servers | Browse/search/filter/select servers | ✅ Implemented |
| Statistics | Server scores, connection history | ✅ Implemented |
| Settings | All app configuration | ✅ Implemented |
| Logs | View, filter, search, export logs | ✅ Implemented |

## 4. User Flows

### 4.1 First Run
1. App opens to Home screen
2. No subscriptions → user taps + on Subscriptions tab
3. Adds subscription URL → servers are parsed and shown
4. User taps a server → VPN permission dialog
5. Permission granted → connection starts
6. Connection confirmed via notification + status indicator

### 4.2 Daily Use
1. Open app → status shown on Home
2. Auto-connect picks best server based on Smart Score
3. View stats (bytes, duration) on Home
4. Switch server from Servers tab

## 5. Constraints

- Min Android SDK: 26 (Android 8.0)
- Target Android SDK: 34 (Android 14)
- Min memory: 1 GB RAM
- Storage: ~50 MB (app + native binaries)
- Network: Internet permission for VPN
