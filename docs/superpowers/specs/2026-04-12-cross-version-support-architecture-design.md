# Cross-Version Support Architecture Design

**Date:** 2026-04-12
**Status:** Draft for review

## Problem Statement

Compukter Kraft currently supports multiple Minecraft versions and multiple loaders in one repository. The loader split is already reasonably controlled, but the repository still carries residual duplication across version-specific `v1_x_x-common` modules.

The design goal is to eliminate duplication of behavior, not to force identical source code across incompatible Minecraft APIs at any cost.

## Key Conclusion

Literal zero duplication across Minecraft versions is not a realistic target when staying in plain Kotlin and supporting multiple incompatible Minecraft APIs in one tree. The practical target is **semantic zero duplication**:

- shared behavior is implemented once;
- version modules only adapt that behavior to Minecraft API differences;
- loader modules only handle bootstrap, registration, hooks, and tiny runtime shims.

## Reference Insight From CC: Tweaked

CC:Tweaked demonstrates a strong multi-loader architecture, not a magical elimination of all version-specific differences.

Its structure is effectively:

- `core` for logic independent of Minecraft;
- `common` for shared Minecraft-facing logic;
- `fabric` and `forge` for loader-specific code;
- narrow bridge interfaces such as `PlatformHelper` when common code must invoke loader-specific behavior;
- loader-owned event subscriptions that delegate into shared hooks.

For Compukter Kraft, the relevant lesson is the boundary discipline, not the exact repository layout.

## Target Architecture

### Layers

#### `compiler`

Unaffected by this design. It already acts as an isolated subsystem.

#### `core`

`core` should become the semantic kernel and single source of truth for behavior.

It should own:

- policies and state-transition logic;
- version-neutral content descriptors;
- persistence semantics;
- interaction intents;
- neutral models for item/block/computer behavior;
- tests that do not require concrete Minecraft API execution.

`core` must not own:

- `net.minecraft.*` types;
- loader APIs;
- concrete Minecraft override methods.

#### Optional future `mc-shared`

This is an optional architectural escape hatch, not an immediate implementation requirement.

If introduced later, it should:

- depend on `core`;
- remain free of `net.minecraft.*`;
- host types that are too Minecraft-shaped for `core`, but still not tied to a concrete version API.

Do not create this module until a real cluster of such types exists.

#### `v1_x_x-common`

Each version-common module should become a thin façade layer.

Its responsibilities:

- map version-neutral models and intents to concrete Minecraft overrides;
- adapt version-local API shapes;
- construct Minecraft objects;
- translate lifecycle hooks into calls into shared behavior.

It must not become the home of business rules.

#### Loader leaf modules

Loader modules should remain limited to:

- bootstrap;
- registry wiring;
- event subscription;
- network registration;
- tiny unavoidable runtime or lifecycle shims.

## Explicit Design Rule

If code answers the question “what should the mod do?”, it belongs above the version layer.

If code answers the question “how does this Minecraft version or loader force us to express it?”, it belongs in the version or loader layer.

## Repository Topology Options

### Option A: Single trunk with multi-version modules

Best when multiple Minecraft versions are actively developed in parallel.

Advantages:

- shared refactors can be applied to every version at once;
- duplication is visible in one tree;
- CI can verify the full support matrix.

Costs:

- more abstraction pressure;
- more build complexity;
- higher cognitive load as versions increase.

### Option B: Long-lived branches per Minecraft line

Best when one Minecraft version is primary and older versions are maintenance only.

Advantages:

- code stays natural to each Minecraft API;
- less compatibility scaffolding;
- easier local reasoning per branch.

Costs:

- commonality becomes process-driven rather than structure-driven;
- bug fixes and features must be ported manually between branches;
- architecture guarantees become weaker across supported versions.

### Option C: Hybrid

One primary trunk for the active Minecraft line, maintenance branches for older lines.

This is the preferred long-term fallback if cross-version parallel development turns out not to be common in practice.

## Recommended Direction

Do not decide the repository topology immediately.

Instead:

1. Continue extracting clearly version-independent behavior into `core`.
2. Classify remaining duplication into categories.
3. Measure how development actually flows across versions.
4. Only then choose between permanent multi-version trunk support and maintenance branches.

This is a low-regret path because `core` extraction pays off under every repository topology.

## Duplication Categories

Every residual duplicate should be classified into one of these buckets:

### 1. Pure behavior duplication

This should be eliminated aggressively.

Examples:

- open-menu decisions;
- state transition rules;
- persistence policy;
- drop policy;
- block entity runtime orchestration.

### 2. Minecraft API shape duplication

This should be minimized, but not forced into artificial abstractions.

Examples already visible in Compukter Kraft:

- `use()` versus `useWithoutItem()` and `useItemOn()`;
- `saveAdditional/load` versus `saveAdditional/loadAdditional` with registries;
- `stack.tag` versus data-component accessors;
- `ResourceLocation(namespace, path)` versus `fromNamespaceAndPath(...)`.

This category is where version façades should live.

### 3. Loader/runtime glue

This should remain thin and explicit.

Examples:

- packet registration;
- event hookup;
- registry binding;
- tiny lifecycle shims.

## Concrete Extraction Targets In This Repository

### Item and computer identity model

Create a version-neutral model for item-carried computer data.

The versions should only implement read/write adapters for their actual `ItemStack` storage APIs.

### Block interaction semantics

Keep the logic for “should open”, “what intent is produced”, and “what interaction result is desired” in shared behavior.

Map that behavior to version-specific overrides in each version module.

### Persistence semantics

Keep “what data is persisted” and “when it changes” above the version layer.

Let version modules own only the concrete method signatures and registry-aware serialization calls.

### Block entity lifecycle policy

Server tick orchestration, computer acquisition, visual state intent, and shutdown/removal policy should live in shared logic.

Version modules should only connect Minecraft lifecycle entry points to that logic.

### Resource and registry helpers

Prefer tiny version-local helper functions over duplicating whole behavior classes because of API constructor drift.

### Binding surface

Existing `ModObjects`-style late binding is directionally correct, but it must remain narrow.

It should expose only the minimum set of Minecraft-typed references or operations needed by shared version code.

It must not become a broad service locator.

## Tooling Recommendation

Do not adopt Kotlin Multiplatform `expect/actual` as the primary mechanism for this problem.

Reason:

- the repository is not solving true cross-platform targets;
- it is solving a matrix of JVM variants with different external APIs;
- `expect/actual` would relocate the complexity instead of removing it;
- build and source-set complexity would likely increase beyond the value gained.

Use explicit modules and explicit façades instead.

## Decision Gates

Before choosing branch-based maintenance or keeping the permanent multi-version module tree, collect evidence from several real tasks:

- how many changes touch more than one Minecraft version;
- how many changes affect only the latest version;
- how often a refactor is truly shared versus only superficially similar;
- how expensive the façade layer feels during routine development.

### Keep the multi-version tree if

- two or more Minecraft versions are actively developed in parallel;
- most meaningful changes land in several versions at once;
- synchronized refactoring is a routine need.

### Move older versions to maintenance branches if

- one Minecraft line dominates active development;
- backports are occasional and deliberate;
- the abstraction tax exceeds the benefit of simultaneous compilation.

## Final Recommendation

Short term:

- keep the current repository topology;
- continue pushing version-independent behavior into `core`;
- keep version modules as façades and loader modules as glue.

Medium term:

- observe actual change patterns over the next several feature or bugfix cycles.

Long term:

- if one Minecraft line clearly dominates, collapse older lines into maintenance branches and keep a CC:Tweaked-like `core/common/loaders` structure in trunk;
- if multiple lines remain active, retain the multi-version tree and optimize for semantic zero duplication rather than literal source identity.

## Non-Goals

- eliminating every line of version-specific code;
- forcing one abstraction over every Minecraft API difference;
- replacing architectural boundaries with procedural porting alone;
- introducing Kotlin Multiplatform as a substitute for explicit version façades.