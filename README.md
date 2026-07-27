# NovaVPN

A modern, lightweight, modular VPN client for Android (and eventually desktop).

> **Phase 1: Android MVP** — Complete.  
> Architecture designed for future Windows, Linux, and macOS support via KMP.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  :app (Android Application)                         │
│  ├── NovaVpnService (VpnService)                    │
│  ├── MainActivity (Compose + Navigation)            │
│  └── BootReceiver (Auto-start)                      │
├─────────────────────────────────────────────────────┤
│  :feature/* (Feature Modules — 6 screens)           │
│  home | subscriptions | servers | statistics        │
│  settings | logs                                    │
├─────────────────────────────────────────────────────┤
│  :core/ui | :core/domain | :core/data | :core/common│
├─────────────────────────────────────────────────────┤
│  :engine/api | :engine/xray | :engine/singbox       │
├─────────────────────────────────────────────────────┤
│  :network | :statistics | :logging | :subscription  │
├─────────────────────────────────────────────────────┤
│  :storage/room | :storage/datastore                  │
└─────────────────────────────────────────────────────┘
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.22 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt 2.50 |
| Database | Room 2.6.1 + KSP |
| Preferences | DataStore |
| Async | Coroutines + Flow |
| Background | WorkManager |
| VPN | Android VpnService |
| Serialization | kotlinx.serialization |
| CI/CD | GitHub Actions |
| Testing | JUnit 5, MockK, Turbine, Truth |

## Modules

| Module | Responsibility |
|---|---|
| `:app` | Application shell, DI wiring, VPN service |
| `:core:domain` | Business entities, repository interfaces, use cases |
| `:core:data` | Repository implementations, Room DB, mappers |
| `:core:common` | Shared utilities (ID gen, formatting, time) |
| `:core:ui` | Theme, navigation, shared composables |
| `:engine:api` | `Engine` interface — abstract VPN engine |
| `:engine:xray` | Xray Core engine implementation |
| `:engine:singbox` | Sing-box engine implementation |
| `:network` | Smart connectivity testing (real HTTP/DNS checks) |
| `:statistics` | Server scoring algorithm |
| `:logging` | Structured in-memory + persistent logging |
| `:subscription` | Subscription URL parsing, VMess/VLESS/Trojan/SS |
| `:storage:room` | Room database entities and DAOs |
| `:storage:datastore` | DataStore settings serializer |
| `:feature:home` | Home screen — connect/disconnect, status |
| `:feature:subscriptions` | Manage subscription URLs |
| `:feature:servers` | Browse/search servers, connect |
| `:feature:statistics` | View server scores and history |
| `:feature:settings` | App settings — engine, DNS, theme |
| `:feature:logs` | View/copy/export/filter logs |

## Key Design Decisions

### Engine Abstraction
```kotlin
interface Engine {
    val type: EngineType
    val state: StateFlow<EngineRuntimeState>
    suspend fun start(config: ServerConfig): Result<Unit>
    suspend fun stop(): Result<Unit>
    // ...
}
```
Xray and Sing-box are pluggable implementations. Adding a new engine requires only implementing this interface.

### Smart Connect
Real connectivity testing — not fake pings:
1. Verify engine process started
2. HTTP HEAD check (1.1.1.1)
3. DNS resolution (google.com)
4. Latency measurement
5. Speed sample (100KB download)

### Smart Score
Multi-factor server scoring:
- Connection success rate (30%)
- Average latency (20%)
- Recent success rate (20%)
- Stability (10%)
- DNS success (10%)
- Speed sample (10%)

### Subscription Parsing
Supports: VMess (base64 JSON), VLESS, Trojan, Shadowsocks, SIP008. Configs parsed to `ServerConfig` domain model.

## Build

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run lint
./gradlew lint

# Run tests
./gradlew testDebugUnitTest
```

## Phase Roadmap

| Phase | Status | Description |
|---|---|---|
| Phase 1: Android MVP | ✅ Complete | Core architecture, engines, UI, subscription, VPN |
| Phase 2: Advanced Testing | 🔜 | Full smart test integration with real engine |
| Phase 3: Auto Connect | 🔜 | Boot receiver, automatic failover, best-server selection |
| Phase 4: Shared Core | 🔜 | KMP shared module for desktop business logic |
| Phase 5: Desktop | 🔜 | Windows, Linux, macOS apps |

## License

MIT
