# Spec Kit — NovaVPN

Spec Kit is the architecture, planning, and development governance system for NovaVPN.

## Overview

Every change to NovaVPN should be reflected in the specs first.  
Specs document what exists, what is planned, and how development should happen.

## Directory Structure

```
specs/
├── spec-kit.yaml          # Configuration
├── README.md              # This file
├── constitution.md        # Engineering constitution & rules
├── product-spec.md        # Product vision & feature specs
├── architecture.md        # Current architecture documentation
├── roadmap.md             # Development roadmap & milestones
├── ci-cd.md               # CI/CD pipeline specification
└── tasks/                  # Task system
    ├── current-bugs.md
    ├── missing-features.md
    ├── tech-debt.md
    └── improvements.md
```

## How to Use

1. **Before coding**: Check specs for the feature or fix.
2. **During coding**: Follow constitution rules.
3. **After coding**: Update specs if architecture changed.
4. **CI must pass** before any spec change is merged.

## Validation

Run validation to verify spec completeness:

```bash
# Check that all required spec files exist
ls specs/*.md | wc -l
```
