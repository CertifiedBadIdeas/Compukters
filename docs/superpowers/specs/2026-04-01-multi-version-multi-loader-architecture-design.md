# Multi-Version Multi-Loader Architecture Design

## Context

Compukter Kraft already has a strong split between `compiler` and `mod`.
That is the right foundation for supporting multiple Minecraft versions and multiple modloader targets from one repository, because the language frontend, bytecode compiler, and VM runtime should remain version-agnostic while Minecraft integration absorbs platform differences.

The immediate target matrix is:

- Fabric 1.20.1
- Forge 1.20.1
- Fabric 1.21.1
- NeoForge 1.21.1

The first supported window is two active Minecraft versions at once, with a bias toward maximum shared code even if build structure becomes more complex.

## Problem Statement

Using a long-lived branch or worktree per Minecraft version does not scale well once feature work continues in parallel. Changes drift, ports become merge-heavy, and it becomes difficult to tell whether a difference is truly version-specific or just branch noise.

Architectury partially addresses the loader axis, but it does not solve the version axis by itself. The missing piece is a project structure that makes version- and loader-specific differences explicit and local.

## Decision

Adopt a single-repository, multi-module architecture with:

- common modules for version-independent logic
- a narrow internal platform abstraction layer
- one runtime leaf module per supported target

The design should treat supported combinations as concrete runtime targets, not as an abstract symmetric matrix. That matters because the loader ecosystem changes across Minecraft generations, for example Forge on 1.20.1 and NeoForge on 1.21.1.

## Architectural Boundaries

The codebase should be separated into the following layers:

### `compiler`

Owns the language frontend, bytecode model, compiler pipeline, runtime, and VM logic.

Rules:

- no dependency on Minecraft classes
- no dependency on loader APIs
- shared by every runtime target without variation

### `core`

Owns mod-specific domain and application logic that is not inherently tied to a particular Minecraft API shape.

Examples:

- computer lifecycle policies
- orchestration around VM execution
- domain models and application services
- abstractions for workbench behavior and network intents

Rules:

- may depend on `compiler`
- must not directly import Fabric, Forge, NeoForge, or version-specific Minecraft APIs
- must only interact with the outside world through the internal platform layer

### `platform-api`

Defines the internal ports that bridge common logic to Minecraft runtime details.

Examples:

- block and item registration ports
- menu and screen opening hooks
- network packet registration and dispatch hooks
- world and block entity integration hooks
- environment services required by computers and workbenches

Rules:

- stable, intentionally narrow surface
- designed around Compukter Kraft use cases, not around mirroring external APIs
- any new Minecraft touchpoint must enter common code through this layer first

### Runtime leaf modules

One module per supported target:

- `fabric-1.20.1`
- `forge-1.20.1`
- `fabric-1.21.1`
- `neoforge-1.21.1`

Each leaf module owns:

- loader bootstrap
- version-specific registration code
- packet wiring
- menu wiring
- adapter implementations for the internal platform API
- any unavoidable target-specific glue code

Rules:

- may depend on `compiler`, `core`, and `platform-api`
- must not become a second home for business logic
- should stay thin and integration-focused

## Dependency Rules

The intended direction is:

`compiler -> core -> platform-api <- runtime-leaf`

Interpretation:

- `core` consumes abstractions from `platform-api`
- runtime targets implement those abstractions
- common code never depends back on a concrete runtime target

This boundary is the core enforcement mechanism. If version migration requires touching `core` for an external API difference, that is a signal that a Minecraft-specific concern leaked upward and the abstraction is too weak or too wide.

## Repository Structure

The current `mod` module should not remain the single home for all Minecraft-facing code if the project wants long-term multi-version support.

Target structure:

- `compiler`
- `core`
- `platform-api`
- `platform-common` if repeated shared runtime code emerges
- `fabric-1.20.1`
- `forge-1.20.1`
- `fabric-1.21.1`
- `neoforge-1.21.1`

`platform-common` is optional and should be added only when duplication actually appears across multiple leaf modules. It should not be introduced preemptively.

## Variation Strategy

There are three legitimate categories of code:

1. Shared by all runtime targets
2. Shared by multiple targets after duplication becomes real
3. Specific to one target

The project should start with category 1 and category 3. Category 2 should be introduced later and only in response to proven repetition.

This avoids building an over-engineered lattice of intermediate modules before the actual seams are understood.

## Migration Strategy From the Existing 1.21.1 Worktree

The current 1.21.1 worktree should be used as a source of classified differences, not merged wholesale.

For each meaningful change in the worktree, classify it into one of four buckets:

- common domain or application logic
- loader-specific integration
- version-specific integration
- accidental refactor noise

Migration rules:

- common logic moves into `core` or another shared module
- loader- or version-specific logic moves into the matching runtime leaf module
- noise is discarded instead of preserved through architecture decisions

This classification-first migration is essential. Without it, the new structure will inherit branch drift and simply re-encode the current chaos in module form.

## Initial Extraction Targets

The first extraction pass should focus on code that usually changes across loaders and versions:

- block and item registration
- menu and screen bootstrap
- networking registration and packet entry points
- block entity integration and lifecycle hooks
- server and client initialization wiring

The following areas should remain aggressively common unless proven otherwise:

- compiler and VM
- computer domain logic
- workbench state management that is not tied to external APIs
- pure UI models and application-layer state containers

## First Milestone

The first milestone is not full feature parity.
It is a validated vertical slice that proves the architecture works across all four initial targets.

Required slice:

- registration succeeds
- a computer can be created in-world
- one menu or screen flow opens correctly
- one client-server network roundtrip works
- a simple VM program starts and produces the expected observable behavior

Feature gaps outside the slice may be temporarily disabled per target during the migration, but the slice itself must behave consistently.

## Build and Maintenance Policy

The project should adopt the following maintenance rules:

- new common code may not directly depend on target-specific APIs
- any new Minecraft API touchpoint must first be modeled in `platform-api`
- runtime leaf modules should remain thin and should not accumulate application logic
- intermediate shared runtime modules may only be added after actual repeated duplication appears

These rules matter more than the exact Gradle layout. Without them, the structure will collapse back into target-specific forks inside shared modules.

## Cost and Trade-Offs

This architecture is primarily a maintenance optimization, not a direct runtime optimization.

Expected costs:

- more Gradle complexity
- more CI work because multiple runtime targets must be built and verified
- more adapter and bootstrap code around platform boundaries
- stricter architectural discipline during feature development

Expected runtime impact:

- negligible in normal use if `platform-api` stays narrow and coarse-grained
- most extra indirection will be interface dispatch and adapter calls, which are insignificant compared to Minecraft tick, rendering, and networking overhead
- the compiler and VM should remain essentially unaffected because they already sit outside the Minecraft integration surface

Main risk:

The biggest danger is not raw runtime performance but over-abstraction. If the project creates too many tiny interfaces or wraps every loader API call in unnecessary layers, the code will become harder to change and review without gaining meaningful portability.

Therefore the design should prefer:

- a small number of intentionally chosen platform ports
- thin runtime adapters
- explicit target-specific modules over magical conditional source tricks

## Rejected Alternatives

### Branch per version with loader split inside each branch

Rejected because it optimizes near-term simplicity at the cost of long-term drift, merge overhead, and behavioral divergence.

### Heavy preprocessing or Stonecutter-style conditional code as the primary strategy

Rejected as the main architecture because it hides differences in build tooling and source annotations instead of making them explicit in module boundaries. It can still be considered later for very small, localized API differences if the team finds a specific, justified use.

## Success Criteria

This design is successful if:

- most feature work lands once in common modules
- runtime-specific fixes are localized to leaf modules
- upgrading one target does not force broad edits across unrelated targets
- worktree or branch-based porting stops being the primary mechanism for compatibility work

## Out of Scope

This design does not yet define:

- the exact Gradle module wiring and plugin setup
- the concrete list of `platform-api` interfaces
- the implementation sequence for extracting code out of the current `mod` module

Those belong in the implementation plan.