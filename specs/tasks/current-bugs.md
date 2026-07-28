# Current Bugs

> Priority: P0 (Critical) → P3 (Low)
> Last updated: Phase 1 — Build Stability completed.

## Phase 1 — Fixed Issues

All Phase 1 build stability bugs have been resolved:

| Bug | Fix | Commit |
|---|---|---|
| CI lint failure — compilation errors | Kotlin 2.0 migration + Compose compiler plugin | `2f7b4aa` |
| `storage:datastore` missing domain dependency | Added `:core:domain` | `6b1cfa9` |
| `logging` missing domain dependency | Added `:core:domain` | `bf99bb1` |
| `domain` missing `javax.inject` | Added explicit dependency | `c44e25d` |
| Compose compiler IR failure | Kotlin 2.0 + `kotlin.plugin.compose` | `2f7b4aa` |
| `EngineError` not a `Throwable` | Extended `Throwable` | `93ae32a` |
| AppNavigation in wrong module (core:ui) | Moved to `:app` | `27f4327` |
| `SettingsSerializer.readAll()` not available | Changed to `readBytes()` | `76097e6` |
| `SettingsSerializer()` called as constructor | Return object reference | `76097e6` |
| `ExperimentalFoundationApi` not opted in | Added `@OptIn` | `f66ee17` |
| `contentOrNull` deprecated | Replaced with `content` | `9986832`, `4f643f8` |
| `add(String)` removed from `JsonArrayBuilder` | Wrapped with `JsonPrimitive` | `44c0b99` |
| `Process.pid()` not available | Removed PID logs | `1db0deb` |
| Missing Timber dependency in `:statistics` | Added `implementation(libs.timber)` | `6268f30` |
| Missing launcher icon | Added vector + adaptive icon | `62ede0e` |
| `BuildConfig` import missing | Added explicit import | `829698e` |
| Hilt multibinding incompatible with Kotlin 2.0 | Replaced with direct registration | `eb4c07a` |
| Dagger kapt with Kotlin 2.0 | Hilt 2.50 → 2.51 | `30b6168` |
| Missing `foregroundServiceType` (Android 14) | Added to manifest | `9f97397` |
| Unclosed KDoc comments | Fixed | `76ff06b` |
| Extra closing braces | Removed | `ddc1fae` |

## Current Status

Phase 1 — Build Stability: ✅ **COMPLETED**

CI pipeline passes all checks:
- `./gradlew lint` ✅
- `./gradlew test` ✅
- `./gradlew assembleDebug` ✅
