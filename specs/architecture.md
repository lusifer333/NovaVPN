# NovaVPN — Architecture Documentation

> Version 1.0.0  
> Current architecture as of Phase 1 (Android MVP)

## 1. Module Map

```
┌──────────────────────────────────────────────────────────────────┐
│                        :app (Android)                            │
│  Application, MainActivity, VPNService, BootReceiver             │
├──────────────────────────────────────────────────────────────────┤
│  :feature/home  :feature/subscriptions  :feature/servers         │
│  :feature/statistics  :feature/settings  :feature/logs           │
├──────────────────────────────────────────────────────────────────┤
│  :core/ui  │  :core/ui  │  :core/domain  │  :core/common         │
├──────────────────────────────────────────────────────────────────┤
│  :engine/api  :engine/xray  :engine/singbox                      │
├──────────────────────────────────────────────────────────────────┤
│  :network  :statistics  :logging  :subscription                   │
├──────────────────────────────────────────────────────────────────┤
│  :storage/room  :storage/datastore                                │
└──────────────────────────────────────────────────────────────────┘
```

**21 modules total.** Each with a single responsibility.

## 2. Layer Responsibilities

### 2.1 Domain Layer (`:core:domain`)

**Responsibility:** Business logic and enterprise rules.

Contains:
- **Models** — `ServerConfig`, `Subscription`, `ConnectionState`, `TestResult`, `ServerScore`, `AppSettings`
- **Repository interfaces** — Contracts for data operations
- **Use cases** — Business operations (Connect, Score, Subscribe, etc.)

**Dependencies:** `:core:common`, `javax.inject`, `kotlinx.serialization`, `kotlinx.coroutines`  
**Forbidden:** Android SDK, Hilt, platform code

### 2.2 Data Layer (`:core:data`)

**Responsibility:** Implement repository contracts, manage persistence.

Contains:
- **Room entities & DAOs** — Database access (5 entities)
- **Repository implementations** — Bridge between domain and storage
- **Mappers** — Entity ↔ Domain model conversion
- **DataStore** — App settings persistence

**Dependencies:** `:core:domain`, `:storage:room`, `:storage:datastore`  
**Forbidden:** UI layer, Compose

### 2.3 Engine Layer (`:engine/*`)

**Responsibility:** VPN tunnel management.

**`api` module:**
- `Engine` interface (start/stop/restart/destroy)
- `EngineContext` — Platform tunnel descriptor
- `EngineManager` — Active engine routing

**`xray` module:**
- `XrayEngine` — Process lifecycle management
- `XrayConfigParser` — `ServerConfig` → Xray JSON

**`singbox` module:**
- `SingboxEngine` — Process lifecycle management
- `SingboxConfigParser` — `ServerConfig` → Sing-box JSON

**Pattern:** Engines are pluggable via `Map<EngineType, Engine>` multibinding with Dagger.

### 2.4 Feature Layer (`:feature/*`)

**Responsibility:** UI screens per feature.

Each feature has:
- **Screen** — Composable function
- **ViewModel** — `@HiltViewModel` with state management
- **UiState** — Data class for UI state

Features route through use cases in `:core:domain`.

### 2.5 Core UI (`:core:ui`)

**Responsibility:** Shared UI components, navigation, theming.

Contains:
- `NovaVPNTheme` — Material 3 colors (light/dark)
- `NovaComponents` — Shared composables (TopBar, ServerListItem, etc.)
- `AppNavigation` — NavHost with bottom navigation

### 2.6 Supporting Modules

| Module | Responsibility |
|---|---|
| `:network` | `SmartTester` — real connectivity checks |
| `:statistics` | `ScoreCalculator` — smart server scoring |
| `:logging` | `NovaLogger` — structured logging (in-memory + Room) |
| `:subscription` | `SubscriptionParser` — parse VMess/VLESS/Trojan/SS |
| `:storage:room` | Room database entities and DAOs |
| `:storage:datastore` | DataStore settings serializer |

## 3. Dependency Graph

```
:app
 ├── :feature:home → :core:domain, :core:ui
 ├── :feature:subscriptions → :core:domain, :core:ui
 ├── :feature:servers → :core:domain, :core:ui
 ├── :feature:statistics → :core:domain, :core:ui
 ├── :feature:settings → :core:domain, :core:ui
 ├── :feature:logs → :core:domain, :core:ui
 ├── :engine:xray → :engine:api
 ├── :engine:singbox → :engine:api
 └── :core:data → :core:domain, :storage:room, :storage:datastore

:core:domain → :core:common
:engine:api → :core:domain
:network → :engine:api
```

**No circular dependencies.**  
**No outward dependencies from domain.**

## 4. Data Flow

### 4.1 Subscription → Server List
```
User adds URL
  → SubscriptionsViewModel
    → AddSubscriptionUseCase
      → SubscriptionRepositoryImpl
        → SubscriptionDao (Room)
  ← Flow<List<Subscription>>

App fetches subscription URL
  → SubscriptionImporter.fetchFromUrl()
    → SubscriptionParser.parse()
      → List<ServerConfig>
  → ServerRepositoryImpl.replaceForSubscription()
    → ServerConfigDao (Room)
  ← Flow<List<ServerConfig>> to UI
```

### 4.2 Connection Flow
```
User taps Connect
  → HomeViewModel.connect(server)
    → ConnectUseCase.connect()
      → EngineManager.activeEngine.start(config)
        → Engine process spawned
        → VPN tunnel established
  → State: Connecting → Connected
  → VPNService foreground notification shown
```

### 4.3 Smart Test Flow
```
Auto Connect triggers
  → GetBestServerUseCase
    → StatisticsRepository.getAllScores()
    → Rank by ScoreCalculator.calculate()
    → Select top server

Manual test
  → SmartTester.test(server)
    → EngineStartStep
    → InternetCheckStep (HTTP HEAD 1.1.1.1)
    → DnsCheckStep (resolve google.com)
    → LatencyStep (3 pings)
    → SpeedSampleStep (100KB download)
  → TestResult stored
  → ScoreCalculator recalculates
  → SmartScore updated
```

## 5. Key Design Patterns

### 5.1 Engine Abstraction

```kotlin
interface Engine {
    val type: EngineType
    val state: StateFlow<EngineRuntimeState>
    suspend fun start(config: ServerConfig): Result<Unit>
    suspend fun stop(): Result<Unit>
    // ...
}
```

New engines implement this interface.  
`EngineManagerImpl` uses Dagger multibindings to collect all engines.  
Feature code never imports engine implementations directly.

### 5.2 Repository Pattern

```kotlin
// Domain (interface)
interface SubscriptionRepository {
    fun observeAll(): Flow<List<Subscription>>
    suspend fun add(subscription: Subscription): String
}

// Data (implementation)
class SubscriptionRepositoryImpl @Inject constructor(
    private val dao: SubscriptionDao
) : SubscriptionRepository {
    // Maps Entity ↔ Domain model
}
```

### 5.3 Score System

Composite score: 7 parameters → 0..100 score → ranked for auto-connect.

## 6. Storage

| Data | Storage | Key |
|---|---|---|
| Subscriptions | Room (`SubscriptionEntity`) | `id` (String) |
| Server configs | Room (`ServerConfigEntity`) | `id` (String) |
| Test results | Room (`TestResultEntity`) | Auto-increment ID |
| Server scores | Room (`ServerScoreEntity`) | `serverId` (String) |
| Log entries | Room (`LogEntryEntity`) | Auto-increment ID |
| App settings | DataStore (JSON) | `app_settings.json` |

## 7. Security Architecture

- VPN permission requested via `VpnService.prepare()`
- Foreground service with persistent notification
- Boot receiver for auto-start (requires user opt-in)
- Subscription data stored in app-private Room database
- Engine binaries in app private directory
- No secrets in source code
