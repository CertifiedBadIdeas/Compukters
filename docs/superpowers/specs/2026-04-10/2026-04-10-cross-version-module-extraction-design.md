# Cross-Version Module Extraction Design

## Goal

Reduce duplication across Minecraft versions and loaders by tightening the module boundaries between `core`, `v1_x_x-common`, and loader leaf modules, while avoiding over-abstraction of Minecraft classes.

## Current Observations

- `modules/core` already contains a substantial amount of reusable runtime, UI, VM, application, and platform contract logic.
- `v1_x_x-common` modules currently contain a mix of true version-compatibility code and code that conceptually belongs to a higher-level shared Minecraft-facing layer.
- Loader leaf modules still contain large amounts of content code, including `Block`, `BlockEntity`, `Item`, `Menu`, `Screen`, `ServerComputer`, context classes, data classes, loot conditions, and network message classes.
- Existing platform contracts such as `PlatformInputProvider` are a good direction, but content registration contracts are still too weak. For example, `PlatformBlockRegistrar` only exposes `registerBlock(name: String)` and cannot describe real content.

## Target Module Boundaries

### `compiler`

Owns the language frontend, compiler, VM runtime, and other logic that is fully independent from Minecraft.

### `core`

Owns cross-version mod logic that answers the question "what does the mod do?"

This includes:

- domain state and policies
- application use-cases
- orchestration and lifecycle logic
- content descriptors
- registry-neutral protocol descriptors
- platform and compatibility contracts
- world-independent behavior for computers, menus, and block interactions

`core` must not directly depend on loader APIs, and should avoid direct dependence on Minecraft inheritance-heavy types unless that code is truly stable across supported versions.

### `v1_x_x-common`

Owns the Minecraft-version compatibility layer. This layer answers the question "how is this behavior expressed through the Minecraft API for this game version?"

This includes:

- `Block`, `BlockEntity`, `Menu`, and `Screen` classes
- version-specific wrappers for NBT, buffers, commands, and level access
- version-specific rendering, input, and UI integration
- codec and serialization glue tied to the Minecraft version
- adapters that translate between `core` contracts and the concrete Minecraft API

These modules should become the home for almost all Minecraft-facing code that is not loader-specific.

### Loader Leaf Modules

Own only the loader bootstrap layer. This layer answers the question "how is the version-specific code wired into Fabric, Forge, or NeoForge?"

This includes:

- mod entrypoints
- event bus hookup
- service registration
- loader-specific network registration
- loader-specific client/server bootstrap wiring

Leaf modules should stop owning substantial gameplay or content behavior.

## Design Decision: Do Not Move `Block` And `BlockEntity` Classes Into `core`

The recommended design is to keep concrete Minecraft classes such as `Block`, `BlockEntity`, `Menu`, and `Screen` in `v1_x_x-common` instead of moving them into `core`.

Reasons:

- These classes are deeply tied to Minecraft inheritance and lifecycle APIs.
- Their signatures and supporting APIs drift between Minecraft versions.
- Moving them into `core` would pull Mojang concepts into a module that should remain primarily behavior-oriented and cross-version.
- The likely result would be a leaky abstraction that is harder, not easier, to maintain.

The correct extraction target is not the classes themselves, but the behavior they currently embed.

## Recommended Extraction Strategy

Use a hybrid model:

- keep concrete Minecraft classes in `v1_x_x-common`
- make those classes thin wrappers
- move policies, state transitions, orchestration, and factory contracts into `core`

For example, instead of attempting to share one concrete `ComputerBlock` class across all versions, define shared behavior in `core` and have version-specific `ComputerBlock` implementations delegate into it.

## What Moves Into `core`

The following categories should be extracted upward into `core` whenever they can be expressed without direct dependence on version-specific Minecraft APIs:

- computer lifecycle policies
- block interaction use-cases
- menu opening rules
- content descriptors for blocks, items, menus, and block entities
- factory contracts for creating computer-backed entities and menus
- state transition logic such as computer on/off/crash state handling
- registry-neutral network protocol description
- world-independent server computer orchestration

## What Stays In `v1_x_x-common`

The following categories should remain in version-common modules:

- concrete `Block`, `BlockEntity`, `Menu`, and `Screen` subclasses
- `BlockState` properties and codecs
- `BlockPlaceContext`, `BlockEntityType`, `MenuType`, and related Minecraft types
- version-specific rendering glue
- buffer, NBT, level, and command helpers when they differ by Minecraft version
- direct world mutation through Minecraft APIs such as `level.setBlock(...)`

## What Leaves Loader Leaf Modules First

The highest-value first move is to thin the loader leaf modules so that they stop owning shared Minecraft-facing content.

First-wave extraction candidates from loader modules into `v1_x_x-common`:

- `block`
- `item`
- `menu`
- `computer`
- `context`
- `data`
- `loot`
- `gui/screen`
- network message and protocol model classes that are not loader-API-specific

What should remain in loader modules after that move:

- entrypoint classes
- loader event subscription classes
- service binding and registry bootstrap
- loader-specific network channel registration adapters

## Descriptor And Contract Expansion

The current registration contracts are not expressive enough to support a clean split.

For example, `PlatformBlockRegistrar.registerBlock(name: String)` cannot describe the data needed to register blocks, block entities, menus, items, and related bindings.

The design should evolve toward descriptor-based registration APIs, such as:

- block descriptors
- block entity descriptors
- menu descriptors
- item descriptors
- optional client binding descriptors

In this model:

- `core` describes what content exists
- `v1_x_x-common` knows which concrete Minecraft classes implement that content for a given version
- loader modules perform the actual registration in their loader API

## Phased Migration Plan

### Phase 1: Loader Thinning

Move shared Minecraft-facing code from loader leaf modules into `v1_x_x-common` until the leaf modules are mostly bootstrap and wiring.

### Phase 2: Structure The Version-Common Layer

Inside each `v1_x_x-common`, organize code into clearer categories so that the module does not become a generic dumping ground:

- `platform/mc`
- `content`
- `runtime`
- `network/model`
- `ui/mc`

### Phase 3: Lift Behavior Into `core`

Refactor Minecraft-facing classes so that they delegate shared behavior into `core` services, policies, descriptors, and factories.

### Phase 4: Normalize Registration

Replace string-based registration contracts with descriptor-driven APIs that can express real content relationships.

### Phase 5: Compatibility Audit

For every remaining duplicate, decide which category it belongs to:

- real loader difference
- real Minecraft version difference
- accidental duplication caused by missing abstraction

### Phase 6: Revisit Block And Block Entity Unification

Only after the descriptor and behavior layers are stable should the project reconsider whether any concrete block or block entity implementation can be merged further.

## Priority Guidance

The project should not start by trying to unify concrete block classes. That work is high-risk and tends to produce abstraction noise.

The priority order should be:

1. thin the loader leaf modules
2. make `v1_x_x-common` the owner of Minecraft-facing shared code
3. extract behavior and contracts into `core`
4. strengthen registration and protocol descriptors
5. re-evaluate remaining duplication only after the new boundaries hold

## Risks

- Over-abstraction of Minecraft concepts into `core`
- Turning `v1_x_x-common` into an unstructured catch-all module
- Mixing loader differences with Minecraft version differences in the same abstractions
- Refactoring concrete block classes too early before contracts are stable

## Success Criteria

- Loader leaf modules become thin bootstrap layers.
- `v1_x_x-common` becomes the clear owner of Minecraft-facing version compatibility.
- `core` becomes the clear owner of shared policies, orchestration, descriptors, and contracts.
- New Minecraft versions require mostly adapter work rather than copy-pasting feature logic.
- Remaining duplication is intentional and categorized, not accidental.