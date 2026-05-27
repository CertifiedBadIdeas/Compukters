# Create NeoForge-First Compatibility Design

## Goal

Add optional integration with Create without leaking Create dependencies or concepts into `core` or `v1_21_1-common`, while keeping the main mod a single mod artifact rather than an addon.

## Scope

This design covers the first iteration of runtime compatibility for:

- block and use interactions
- kinetic or capability-style bridges
- automation hooks

This design does not cover:

- recipe or datagen integration
- Fabric Create compatibility implementation
- a generalized multi-mod compatibility framework

## Constraints

- `core` must remain free of Minecraft and mod dependencies
- `v1_21_1-common` must remain free of direct Create dependencies
- Create must be an optional dependency
- the mod must still launch and function when Create is absent
- the implementation must fit the current thin loader-leaf architecture

## Chosen Approach

Use a dedicated internal NeoForge compatibility subproject:

- `modules/v1_21_1/v1_21_1-create-neoforge`

This module is an internal Gradle subproject, not a separate public addon mod. It is wired into the main NeoForge leaf so the shipped result remains one mod.

## Module Responsibilities

### `core`

- unchanged
- contains no Create-aware APIs, types, or abstractions

### `v1_21_1-common`

- unchanged by default
- may expose small neutral extension points later only if proven necessary by compatibility code
- must not import or reference Create classes

### `v1_21_1-create-neoforge`

- owns all direct imports of `com.simibubi.create.*`
- owns all Create-specific registration, hooks, and adapters
- owns integration logic for runtime interactions with existing Compukter Kraft blocks, entities, and systems

### `v1_21_1-neoforge`

- remains the bootstrap leaf
- depends on `v1_21_1-create-neoforge`
- decides when compatibility initialization is invoked
- keeps loader bootstrap logic separate from compatibility implementation details

## Build and Packaging Model

### Project inclusion

Add the new subproject in `settings.gradle.kts` next to the existing version modules.

### Leaf wiring

`v1_21_1-neoforge` depends on `v1_21_1-create-neoforge` and includes its main source set in the same mod container, following the existing pattern already used to compose the NeoForge leaf from shared modules.

### Dependencies

Inside `v1_21_1-create-neoforge`:

- use optional compile-time dependency on Create for source compilation
- use optional runtime dependency in development runs when needed for testing
- do not make Create a mandatory runtime dependency for end users

### Metadata

Declare Create as an optional dependency in NeoForge mod metadata so the mod loads correctly both with and without Create present.

## Runtime Activation

Compatibility initialization follows this sequence:

1. NeoForge leaf bootstrap starts normally.
2. Bootstrap checks whether Create is loaded.
3. If Create is absent, compatibility initialization exits without side effects.
4. If Create is present, the leaf calls a dedicated compatibility bootstrap such as `CreateCompatBootstrap.init()`.
5. The compatibility module registers its hooks, adapters, and integration behavior.

This keeps all class loading of Create-aware code behind the compatibility boundary and avoids accidental hard dependency failures.

## Code Boundary Rules

### Allowed

- Create imports only inside `v1_21_1-create-neoforge`
- compatibility code acting as an external consumer of existing Compukter Kraft Minecraft-facing classes
- small neutral hooks added to `v1_21_1-common` only when real integration pressure justifies them

### Forbidden

- Create imports in `core`
- Create imports in `v1_21_1-common`
- Create-specific names or types in shared public surfaces unless the shared layer is explicitly a compatibility boundary, which it is not in this iteration
- preemptive abstraction layers built only for hypothetical future Fabric parity

## Future Fabric Strategy

The first iteration is explicitly NeoForge-first.

Fabric must not implemented in now series

## Success Criteria For Iteration One

- `core` remains unchanged and Create-free
- `v1_21_1-common` remains Create-free
- NeoForge build compiles with the new compatibility module
- game launches without Create installed
- game launches with Create installed
- compatibility code activates only when Create is present
- at least one concrete integration path works end to end for block or automation behavior

## Risks And Mitigations

### Risk: premature abstraction for future Fabric support

Mitigation:

- start with NeoForge-only compatibility module
- delay shared extraction until duplication is real

### Risk: leaking Create concepts into shared modules

Mitigation:

- enforce a strict rule that all Create imports stay inside the compatibility module
- prefer neutral extension points only when unavoidable

### Risk: runtime failures when Create is absent

Mitigation:

- gate compatibility bootstrap behind loader presence checks
- keep Create-aware types out of always-loaded code paths

## Recommendation

Proceed with a dedicated `v1_21_1-create-neoforge` internal compatibility module, wired into the existing NeoForge leaf, with strict isolation of all Create-aware code. This gives a clean NeoForge-first starting point without committing the project to a premature two-loader compatibility architecture.