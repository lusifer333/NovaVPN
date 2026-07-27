# NovaVPN Engineering Constitution

> Version 1.0.0  
> Purpose: Define immutable engineering principles for NovaVPN development.

## Article I — Architecture

### Section 1.1: Clean Architecture

Clean Architecture must be followed. The dependency rule is inviolable:

```
┌──────────────────────────────────┐
│  UI (Compose Screens)            │
├──────────────────────────────────┤
│  ViewModels/Framework (Hilt)     │
├──────────────────────────────────┤
│  Use Cases (Domain logic)        │
├──────────────────────────────────┤
│  Entities / Domain Models        │
└──────────────────────────────────┘
     ▲ dependency direction
     │ (outer depends on inner)
```

**Dependencies point inward. Never outward.**

### Section 1.2: Domain Independence

The `:core:domain` module is the heart of the application.  
It must remain **pure Kotlin** with:

- ✅ No Android framework imports
- ✅ No Hilt/Dagger dependencies  
- ✅ No platform-specific code
- ✅ `javax.inject` is permitted for constructor injection
- ✅ kotlinx.serialization is permitted
- ✅ kotlinx.coroutines is permitted

### Section 1.3: Module Dependency Rule

Allowed dependencies between modules:

| Module | Depends On | Forbidden Deps |
|---|---|---|
| `:core:domain` | `:core:common` | Android, Hilt |
| `:core:data` | `:core:domain`, `:storage:*` | UI, Compose |
| `:engine:api` | `:core:domain` | Platform specifics |
| `:engine:xray` | `:engine:api` | UI, ViewModels |
| `:engine:singbox` | `:engine:api` | UI, ViewModels |
| `:feature:*` | `:core:domain`, `:core:ui` | Engine impls directly |
| `:app` | All modules | Circular deps |

### Section 1.4: Engine Abstraction

No feature module may import an engine implementation directly.  
All engine interaction goes through `Engine` interface in `:engine:api`.

```kotlin
// ✅ CORRECT
val engine: Engine = engineManager.activeEngine

// ❌ WRONG — never import XrayEngine directly in a feature
```

## Article II — Code Quality

### Section 2.1: Kotlin Standards

- All new code is Kotlin.
- Prefer `val` over `var`.
- Prefer immutability.
- Use `data class` for models.
- Use `sealed class` / `sealed interface` for state hierarchies.
- Use `Flow` for reactive streams.
- Use `StateFlow` for observable state holders.
- Use `Result` or sealed class for operation outcomes.

### Section 2.2: Class Design

- Single Responsibility Principle.
- One class = one concern.
- Maximum constructor parameters: 5 (prefer data holders).
- Use interfaces for abstraction boundaries.

### Section 2.3: Naming

- Packages: `com.novavpn.<layer>.<feature>`
- Classes: PascalCase
- Functions/Properties: camelCase
- Constants: UPPER_SNAKE_CASE or top-level `val`
- Composables: PascalCase, noun describing UI element

### Section 2.4: Dependency Injection

- Hilt is the DI framework.
- `@Inject constructor` is preferred over `@Provides` where possible.
- Modules provide interfaces, bind implementations.
- Domain classes use `javax.inject.Inject` only (no Hilt imports).
- Hilt modules must be in `:core:data` or `:app` (outer layers).

## Article III — Development Process

### Section 3.1: CI Gate

- CI must pass before merging to `main`.
- CI runs: lint → test → assembleDebug.
- Lint must have zero errors.
- Test must all pass.
- APK must build successfully.

### Section 3.2: Commit Discipline

- Every commit is a meaningful unit.
- Messages follow conventional commits format:
  - `feat:` new feature
  - `fix:` bug fix
  - `refactor:` code change without behavior change
  - `docs:` documentation only
  - `ci:` CI/CD changes
  - `spec:` specification changes
  - `chore:` maintenance

### Section 3.3: Spec-First Development

- Check specs before implementing.
- Update specs when architecture changes.
- Document new features in product-spec.md before coding.
- Tasks in `specs/tasks/` drive daily work.

## Article IV — Security

### Section 4.1: Secrets

- Never commit secrets to version control.
- API keys, tokens, passwords in `.env` only (gitignored).
- VPN tokens from subscriptions are stored encrypted.

### Section 4.2: Input Validation

- All subscription URL inputs must be validated before parsing.
- External data (subscription responses) must be treated as untrusted.
- Parsers must never crash on malformed input.

### Section 4.3: VPN Config

- VPN configurations are user data and must be protected.
- Do not log raw proxy configurations.

## Article V — Platform Strategy

### Section 5.1: Android First

- Android is the primary target.
- All new features must work on Android first.

### Section 5.2: Future-Proofing

- Business logic lives in `:core:domain` (pure Kotlin).
- When adding platform code, isolate it behind interfaces.
- Design for KMP compatibility where feasible.
- The engine abstraction (`:engine:api`) must remain platform-agnostic.

---

*This constitution is living. Changes require review and must not violate existing rules.*
