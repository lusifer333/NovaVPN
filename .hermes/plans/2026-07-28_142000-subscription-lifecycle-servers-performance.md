# Subscription Lifecycle & Server List Performance Plan

**Goal:** Implement proper subscription disable lifecycle (hide servers without deleting) and optimize for 20,000+ servers.

---

## Architectural Audit

### Current Data Flow

```
SubscriptionsViewModel.addOrUpdate()
→ AddSubscriptionUseCase → SubscriptionRepository → SubscriptionDao.insert()
→ refreshSubscription(id)
  → RefreshSubscriptionUseCase
    → SubscriptionImporter.importFromUrl()
      → SubscriptionParser.parse() → List<ServerConfig>
    → ServerRepository.replaceForSubscription(subscriptionId, servers)
      → ServerConfigDao.deleteBySubscription()
      → ServerConfigDao.insertAll()
    → SubscriptionRepository.markUpdated()
```

**Servers Consumed By:**
- `ServersViewModel`: `serverRepository.observeAll()` → **no filtering**
- `HomeViewModel`: `serverRepository.observeAll()` → **no filtering**
- `GetBestServerUseCase`: `serverRepo.observeAll()` + `statsRepo.getAllScores()` → **no filtering**

### Current Connection State

- `ConnectUseCase` (Singleton): `MutableStateFlow<ConnectionState>`, stores `currentServer`
- `ObserveConnectionStateUseCase`: exposes connection state as Flow
- No persistence of active connection
- No awareness of subscription enabled/disabled state
- `EngineManagerImpl`: delegates to actual engine, has no subscription awareness

### Current Subscription State

- `SubscriptionEntity` has `isEnabled: Boolean` 
- `SubscriptionDao.setEnabled()` exists and works
- `SubscriptionRepository.setEnabled()` exists
- **BUT** `ServerRepository.observeAll()` ignores subscription state entirely
- Foreign key with CASCADE: deleting subscription → deletes servers (we must NOT delete on disable)

### Performance Bottlenecks

1. **No subscription filter in server queries** — every query emits ALL servers
2. **`observeAll()` reads every row from DB** — 20K servers = 20K rows loaded
3. **No `@Transaction` on bulk inserts** — Room doesn't batch, each insert writes separately
4. **No index on `subscription.isEnabled`** — Room scans all rows
5. **LazyColumn on ServersScreen** — no stable key verification needed but should verify
6. **ServerListItem** — need to check for expensive recomposition

---

## Implementation Plan (8 Tasks)

### Task 1: Add DAO query for selectable servers (subs-enabled filtered)

**Files to modify:**
- `storage/room/src/main/kotlin/com/novavpn/storage/room/dao/Daos.kt`

**Changes:**
- Add `observeSelectable()` — JOIN server_configs with subscriptions WHERE isEnabled = 1
- Add index for `subscriptions.isEnabled`
- Wrap `insertAll` in `@Transaction`

### Task 2: Add repository methods for subscription-aware queries

**Files to modify:**
- `core/domain/src/main/kotlin/com/novavpn/domain/repository/Repositories.kt` (interface)
- `core/data/src/main/kotlin/com/novavpn/data/repository/ServerRepositoryImpl.kt`

**Changes:**
- Add `observeSelectable(): Flow<List<ServerConfig>>` to ServerRepository
- Add `isServerFromEnabledSubscription(serverId: String): Boolean` to ServerRepository
- Add `getConnectedServer(): ServerConfig?` to ServerRepository
- Implement in ServerRepositoryImpl using DAO JOIN query
- Wrap `replaceForSubscription` in `@Transaction`

### Task 3: Update ViewModels to use selectable servers

**Files to modify:**
- `feature/servers/src/main/kotlin/com/novavpn/feature/servers/ServersViewModel.kt`
- `feature/home/src/main/kotlin/com/novavpn/feature/home/HomeViewModel.kt`
- `feature/subscriptions/src/main/kotlin/com/novavpn/feature/subscriptions/SubscriptionsViewModel.kt`
- `core/domain/src/main/kotlin/com/novavpn/domain/usecase/server/ServerUseCases.kt`

**Changes:**
- ServersViewModel: switch from `observeAll()` to `observeSelectable()`
- HomeViewModel: switch to `observeSelectable()` for server list
- SubscriptionsViewModel: server counts should check subscription state
- Add `ObserveSelectableServersUseCase`

### Task 4: Connection safety — prevent connect to disabled subscription servers

**Files to modify:**
- `core/domain/src/main/kotlin/com/novavpn/domain/usecase/connection/ConnectionUseCases.kt`
- `core/domain/src/main/kotlin/com/novavpn/domain/usecase/server/ServerUseCases.kt`

**Changes:**
- `ConnectUseCase.connect()`: add pre-check — reject if server from disabled subscription
- `GetBestServerUseCase`: only consider selectable servers
- Keep currently connected server running even if subscription is disabled

### Task 5: Performance — Room transaction, batch insert, indexes

**Files to modify:**
- `storage/room/src/main/kotlin/com/novavpn/storage/room/entity/Entities.kt`
- `storage/room/src/main/kotlin/com/novavpn/storage/room/dao/Daos.kt`
- `core/data/src/main/kotlin/com/novavpn/data/repository/ServerRepositoryImpl.kt`

**Changes:**
- Add index on `subscriptions.isEnabled`
- Wrap `deleteBySubscription` + `insertAll` in `@Transaction`
- Remove individual Flow emissions by using `@Transaction` + direct query for readback
- Add `@Transaction` on the Replace flow

### Task 6: Performance — UI optimization for large lists

**Files to modify:**
- `feature/servers/src/main/kotlin/com/novavpn/feature/servers/ServersScreen.kt`
- `feature/servers/src/main/kotlin/com/novavpn/feature/servers/ServersViewModel.kt`
- `core/ui/src/main/kotlin/com/novavpn/ui/components/NovaComponents.kt`

**Changes:**
- Verify LazyColumn stable keys (already `key = { it.id }`)
- Avoid recomposition in ServersScreen: use `derivedStateOf` for filteredServers
- Check ServerListItem for expensive operations

### Task 7: Tests

**Files to create:**
- `core/data/src/test/kotlin/com/novavpn/data/repository/ServerRepositoryTest.kt`

**Test scenarios:**
1. Disable subscription → servers disappear from selectable list
2. Disable subscription while connected → connection state stable
3. Re-enable subscription → servers return without re-download
4. Import 20,000 servers → no freeze, completes, UI responsive
5. Regression: add/refresh/delete subscription, server selection

### Task 8: Commit, build, release

**Changes:**
- Commit all changes
- Push to GitHub
- Create v0.2.0-alpha release