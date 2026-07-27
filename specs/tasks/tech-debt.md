# Technical Debt

> Known code quality issues to address.

## T-01: Remove Hilt from Domain Layer

**Priority:** P1  
**Description:** `:core:domain` should not depend on Hilt. Currently uses `javax.inject.Inject` only (which is permitted), but should verify no `hilt` imports exist.  
**Files affected:** All files in `:core:domain`  
**Acceptance criteria:** Domain module compiles without `com.google.dagger` or `hilt` imports.

---

## T-02: Tests Missing

**Priority:** P0  
**Description:** No unit tests exist for any module. Repository, use case, parser, and score logic all need tests.  
**Acceptance criteria:** At least `SubscriptionParser`, `ScoreCalculator`, and `SmartTester` have unit tests.

---

## T-03: No Launcher Icon

**Priority:** P1  
**Description:** App uses default Android icon — needs proper branding.  
**Acceptance criteria:** Custom vector drawable icon in core/ui module.

---

## T-04: Gradle Configuration Cache Disabled

**Priority:** P2  
**Description:** `configuration-cache=false` in `gradle.properties`. Should be re-enabled for faster builds.  
**Acceptance criteria:** `configuration-cache=true` works without serialization errors.

---

## T-05: No ProGuard Rules for Serialization

**Priority:** P2  
**Description:** ProGuard rules exist but may not cover all kotlinx.serialization classes.  
**Acceptance criteria:** Release build does not crash on deserialization.

---

## T-06: Missing Gradle Version Catalog Standardization

**Priority:** P3  
**Description:** Some dependencies are not in the version catalog (like `javax.inject:javax.inject:1`). Move all to `libs.versions.toml`.  
**Acceptance criteria:** All dependency versions are in the catalog.
