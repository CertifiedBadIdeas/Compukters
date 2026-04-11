# Residual Cross-Version Extraction Design

## Goal

Finish the unfinished part of the cross-version/module-boundary migration so that:

- `core` owns as much behavior and policy as possible
- `v1_x_x-common` owns Minecraft-version-specific API glue and concrete Minecraft classes
- loader modules are reduced to bootstrap, loader wiring, registration binding, and network hookup

This design covers the remaining code that is still split between `v1_x_x-common` and loader modules after the first migration pass.

## Corrected Boundary

### `core`

`core` should own every part of the system that can be expressed without a hard dependency on Minecraft API classes or loader APIs.

That includes:

- behavior and policies
- orchestration and lifecycle rules
- content descriptors
- registry-neutral contracts
- state-transition logic
- non-Minecraft-facing decision logic used by blocks, block entities, loot, menus, and runtime classes

### `v1_x_x-common`

`v1_x_x-common` should own the Minecraft-facing layer for one Minecraft version.

That includes:

- concrete `Block`, `BlockEntity`, `Menu`, and `Screen` classes
- version-specific Minecraft API glue
- block-state properties, codecs, NBT/buffer/level wrappers
- adapters that translate `core` behavior into Minecraft API calls

These modules may contain Minecraft code, but they should not contain loader-specific APIs such as Fabric registration calls, Forge `RegistryObject`, or NeoForge `DeferredHolder` semantics in their public design.

### Loader Modules

Loader modules should be reduced to binding-only modules.

They should own only:

- mod entrypoints
- loader event hookup
- registry binding and holder unwrapping
- client bootstrap wiring
- packet/channel registration
- loader-specific service wiring

Loader modules should not own gameplay/content behavior or concrete shared content implementations when those implementations are only blocked by holder-wrapper differences.

## What Was Left Unfinished

The first migration pass moved the safe shared code out of loader modules, but it stopped where code depended on loader-specific registry holder types or loader-specific construction paths.

As a result, the following classes remain duplicated or loader-owned even though they conceptually belong above the loader layer:

- `block/ComputerBlock.kt`
- `block/ComputerBlockEntity.kt`
- large parts of `block/AbstractComputerBlock.kt`
- large parts of `block/AbstractComputerBlockEntity.kt`
- `loot/BlockNamedEntityLootCondition.kt`
- `loot/HasComputerIdLootCondition.kt`
- `loot/PlayerCreativeLootCondition.kt`
- `context/ComputerIdentitySavedData.kt` for the 1.21.1 family

These files were not blocked by Minecraft-version drift alone. They were blocked mainly by missing abstractions around registry references and factory access.

## Root Cause

`v1_x_x-common` still lacks a small compatibility layer for working with registry-backed references independently from loader wrapper types.

Today, the remaining loader-owned code still knows about differences such as:

- `Supplier<T>`
- `RegistryObject<T>`
- `DeferredHolder<*, T>`

Until that difference is abstracted, `common` cannot own block/entity/loot classes cleanly.

## Design Decision

The next step is not to move more code directly into `core` first.

The correct order is:

1. move the remaining concrete Minecraft-facing classes from loader modules into `v1_x_x-common`
2. once those classes live in `common`, extract their behavior and policies upward into `core`

This preserves the intended boundary:

- `common` becomes the owner of all Minecraft-facing compatibility code for a version
- `core` becomes the owner of all behavior that can be expressed without Minecraft API coupling

## Required New Abstraction

Introduce a minimal holder/reference abstraction in `v1_x_x-common` for the kinds of registry-backed objects that currently block migration.

Examples of what this abstraction must cover:

- block entity type access
- menu type access
- loot condition type access
- saved-data factory/access when loader APIs diverge

This abstraction should be intentionally narrow.

It should not attempt to hide the entire registry system. Its only job is to let `common` depend on neutral references while each loader provides the binding that unwraps or resolves the real object.

## Extraction Targets

### Immediate Targets: loader -> `v1_x_x-common`

After the holder abstraction exists, the next files to move are:

- `ComputerBlock`
- `ComputerBlockEntity`
- the common portion of `AbstractComputerBlock`
- the common portion of `AbstractComputerBlockEntity`
- loot condition implementations
- `ComputerIdentitySavedData` in places where factory/access differences can be pushed behind adapters

The end state is that these classes live in `v1_x_x-common`, not in loader modules.

### Next Targets: `v1_x_x-common` -> `core`

Once the concrete classes live in `common`, extract behavior into `core` aggressively wherever Minecraft API is not strictly required.

Examples:

- default-state decisions
- interaction rules and use-case routing
- state transitions
- menu-opening policy
- block-entity lifecycle decisions
- loot predicate logic that can be represented in neutral models
- registry-neutral relationships between blocks, block entities, items, and menus

`common` should retain only the code that translates those behaviors into actual Minecraft API calls.

## Phase Plan

### Phase 1: Holder Abstraction

Add minimal reference/binding abstractions to `v1_x_x-common` for the remaining registry-backed content.

### Phase 2: Move Block Layer To `common`

Move the concrete block and block entity layer out of loader modules and into `v1_x_x-common`, keeping only loader binding glue below.

### Phase 3: Move Loot Layer To `common`

Move the remaining loot conditions into `v1_x_x-common` using the same holder abstraction.

### Phase 4: Normalize Saved Data Ownership

Unify `ComputerIdentitySavedData` ownership so that loader modules only provide loader-specific wiring where required.

### Phase 5: Extract Behavior To `core`

Refactor the newly centralized `common` classes into thin wrappers over behavior/services/policies defined in `core`.

This phase is limited to pure policy slices and neutral models that can be expressed without Minecraft API classes. It is not a wholesale move of concrete `Block`, `BlockEntity`, `Menu`, `Screen`, or loot-condition classes into `core`.

Examples of the intended extraction boundary include:

- default block-state policy
- menu-open gating rules
- block-entity lifecycle/state-transition decisions
- loot predicate logic represented in neutral models

### Phase 6: Final Loader Audit

Audit each loader module. Any remaining production class outside bootstrap/binding/network-wiring categories must be justified explicitly or moved upward.

## Non-Goals

This design does not attempt to:

- remove all Minecraft code from `v1_x_x-common`
- unify different Minecraft versions behind one fake universal API
- hide all registry behavior behind a giant abstraction layer

Those directions would increase complexity without improving the actual module boundary.

## Success Criteria

The migration is only complete when all of the following are true:

- loader modules contain only bootstrap and binding logic
- concrete shared content implementations live in `v1_x_x-common`
- behavior and policy that do not require Minecraft API live in `core`
- remaining duplication is either true Minecraft-version drift or true loader runtime wiring
- there are no content/behavior classes stranded in loader modules merely because of holder-wrapper differences