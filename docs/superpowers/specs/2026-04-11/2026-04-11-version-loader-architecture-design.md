# Version and Loader Architecture Design

Date: 2026-04-11
Status: Drafted for review

## Context

Compukter Kraft currently targets multiple Minecraft versions and multiple loaders.
The repository already moved substantial Minecraft-facing code into per-version `*-common` modules and reduced loader leaf modules to a smaller surface.

The remaining problem is strategic rather than purely mechanical:

- duplicated code still exists across versions and loaders;
- adding a new Minecraft version remains expensive and mentally heavy;
- some code that is only transitively tied to Minecraft still lives in Minecraft-facing modules;
- it is not yet proven whether `1.21.1` and `1.21.11` are close enough to justify a shared physical `1.21.x` module.

This design defines a conservative target architecture that lowers long-term maintenance cost without creating false abstractions.

## Goals

- Make adding a new Minecraft version cheaper and more predictable.
- Reduce duplicated code where the duplication is accidental rather than required by real API drift.
- Keep architectural boundaries honest across Minecraft versions and loaders.
- Move business logic out of Minecraft shells whenever Minecraft is only a host for mod-owned state.

## Non-Goals

- Do not force `1.20.1` and `1.21.x` into a fake universal abstraction layer.
- Do not use separate git branches as the primary mechanism for version architecture.
- Do not rename all modules immediately.
- Do not assume that `1.21.1` and `1.21.11` belong in one shared physical module before drift is measured.

## Why Not Git Branches as the Main Solution

Using separate git branches per supported version is attractive because each branch stays locally simple.
However, for this project it would move the version boundary into git history instead of keeping it explicit in the codebase.

That has several costs:

- shared bugfixes must be cherry-picked or manually ported;
- architectural improvements drift apart over time;
- it becomes harder to distinguish real API drift from branch desynchronization;
- multi-loader support multiplies the synchronization burden.

Git branches remain acceptable as a release or support strategy, but not as the primary architecture strategy.

## Target Architecture

The target architecture keeps physical modules version-specific while tightening their responsibilities.

Target layering:

```text
compiler
core
v1_20_1-common
v1_20_1-fabric
v1_20_1-forge
v1_21_1-common
v1_21_1-fabric
v1_21_1-neoforge
v1_21_11-common
v1_21_11-fabric
v1_21_11-neoforge
```

Responsibilities:

- `compiler`
  - language frontend, bytecode compiler, VM runtime;
  - no Minecraft dependencies.
- `core`
  - shared pure domain and application logic;
  - mod-owned state machines, orchestration, descriptors, policies, UI description, workbench state, contracts and service logic;
  - should not accumulate new accidental `net.minecraft.*` dependencies.
- `v1_x_x-common`
  - version-specific Minecraft adapters shared by all loaders of one exact version;
  - blocks, items, menus, block entities, version-specific codecs and helpers, Minecraft-side adapters.
- loader leaf modules
  - loader entrypoints, registry hookup, event hookup, network registration, tiny unavoidable shims.

This preserves explicit version boundaries while still enabling aggressive upward extraction into `core`.

## Core Boundary

`core` is the intended shared-pure layer.
If parts of the current `core` still contain Minecraft-bound assumptions, they must be split rather than grandfathered in.

Boundary rule:

- code stays in `core` if its logic can be tested and reasoned about without Minecraft runtime behavior;
- code leaves `core` only when it genuinely depends on Minecraft object models, version-specific constructors, or runtime contracts;
- loader APIs do not belong in `core`.

The key distinction is ownership of decisions.
If Minecraft only hosts data or triggers lifecycle callbacks, the decision-making should usually live in `core`.

## What Belongs in Version-Specific Common Modules

Each `v1_x_x-common` module should contain code that is shared across loaders for one exact version and is truly tied to that version's Minecraft API.

Typical contents:

- Minecraft object adapters for blocks, items, menus, block entities, containers, screens;
- version-specific serialization and buffer adaptation;
- version-specific item data access and helper extensions;
- saved-data and world access helpers that are common within the version;
- descriptor-to-Minecraft binding layers where the binding logic depends on version-specific Minecraft constructors or types.

What should not live there:

- loader lifecycle and bootstrap plumbing;
- loader-specific registry or event bus integration;
- business rules that only happen to sit inside Minecraft classes today;
- pure orchestration or state transitions.

## Thin Loader Rule

Loader modules should be treated as leaf integration points.
They should trend toward the following shape:

- entrypoint and initialization;
- loader registry plumbing;
- loader packet registration and event wiring;
- minimal shims for real loader/runtime divergences.

They should not own shared game behavior.
When code is identical across Fabric, Forge, or NeoForge within the same Minecraft version, it should be pushed upward into the corresponding `v1_x_x-common` module.

## Extracting Transitively Minecraft-Dependent Logic

Some code appears Minecraft-bound only because it sits inside a Minecraft subclass or lifecycle method.
That alone is not a sufficient reason to keep the logic in a Minecraft-facing module.

Logic is a candidate for extraction into `core` when:

- it reads or writes primarily mod-owned state;
- it makes decisions from projected values rather than Minecraft runtime behavior;
- it can return intents or effects instead of mutating Minecraft objects directly;
- the Minecraft object mostly acts as a shell or carrier.

Recommended patterns:

1. Extracted state holder
   - keep a mod-owned service or state object inside a Minecraft shell;
   - move decision-making into that state object.
2. Context projection
   - build small immutable inputs from Minecraft state and pass them into `core`.
3. Effect return
   - let `core` return intents or effects, and let Minecraft-facing code apply them.

This keeps `v1_x_x-common` focused on adaptation rather than owning business logic.

## API-Line Hypothesis vs Physical Modules

There is a useful architectural idea that some exact versions may belong to the same broader API family.
However, this must remain a hypothesis until it is proven by code-level drift analysis.

For now:

- physical modules remain exact-version modules;
- a broader API line is treated only as a reuse hypothesis;
- no physical `1.21.x` consolidation is performed until compatibility is demonstrated.

This avoids premature generalization.

## 1.21.1 vs 1.21.11 Decision Rule

The repository currently shows separate version conventions for `1.21.1` and `1.21.11`, and the surrounding ecosystem versions already move noticeably between them.

Examples:

- Architectury rises from `13.0.2` to `19.0.1`;
- Fabric Loader rises from `0.16.14` to `0.18.6`;
- Fabric API rises from `0.110.0+1.21.1` to `0.141.3+1.21.11`;
- NeoForge rises from `21.1.222` to `21.11.42`.

That does not prove source incompatibility, but it does prove that broad `1.21.x` unification should not be assumed.

Decision rule:

1. Populate `v1_21_11` using `v1_21_1-common` as the baseline.
2. Record every required change.
3. Classify each change as one of:
   - mappings-only;
   - dependency or tooling-only;
   - real source-level API drift.
4. Only if source-level drift is small and localized should a shared API-family abstraction be considered.

If drift reaches core gameplay adapters such as menus, block entities, item semantics, network payload handling, or rendering hooks, then the exact-version split remains the correct shape.

## Migration Strategy

The migration should happen incrementally.

### Phase 1: Freeze boundary rules

Adopt explicit rules:

- `core` should not gain new accidental Minecraft dependencies;
- `v1_x_x-common` should not gain loader lifecycle plumbing;
- loader modules should not gain new business logic.

### Phase 2: Finish loader slimming

Continue extracting code from loader modules into version-specific common modules where loader differences are only incidental.
Binding surfaces such as `ModObjects` are acceptable if they help remove shared Minecraft-facing content from leaf modules.

### Phase 3: Purify `core`

Audit the current `core` and split out code that is only falsely shared with Minecraft-bound assumptions.
Move truly shared logic into `core`, and move genuine version-bound adapters into exact-version common modules.

### Phase 4: Apply reuse audit for new versions

When a new Minecraft version is introduced:

- first treat the previous exact-version common module as a baseline;
- measure the real drift;
- only then decide whether reuse can stay implicit, whether helper extraction is warranted, or whether a new version-specific path should remain separate.

## Testing and Verification Strategy

Three levels of verification should back the architecture.

### 1. Structural boundary checks

Examples:

- `core` must not import `net.minecraft.*` unless explicitly approved and documented;
- loader modules must not contain domain or orchestration logic;
- version-common modules must not contain loader bootstrap code.

These checks can be implemented with simple structural tests or search-based assertions.

### 2. Unit tests for lifted logic

Whenever logic is extracted from Minecraft-facing classes into `core`, it should gain direct tests that run without Minecraft runtime.

### 3. Matrix smoke tests

A minimal set of representative runtime checks should cover at least:

- one loader on `1.20.1`;
- one loader on `1.21.1` or `1.21.11`;
- targeted checks around registry, networking, and other real drift boundaries.

## Consequences

Expected benefits:

- new versions are added through measured reuse instead of blind copying;
- shared logic becomes truly shared in `core`;
- exact-version modules remain honest about real API differences;
- loader modules become smaller and easier to reason about.

Trade-offs:

- this design keeps more physical modules than an aggressively unified `1.21.x` approach;
- some duplication will remain where version drift is real;
- architectural discipline is required to prevent `v1_x_x-common` from becoming a new dumping ground.

## Recommended Default Rule

Until proven otherwise, prefer this rule:

- version-specific modules are the default;
- upward extraction into `core` is the primary deduplication mechanism;
- broader version-family abstractions are optional and must be earned by compatibility evidence.