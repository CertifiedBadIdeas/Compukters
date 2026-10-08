---
layout: default
title: Maintain first-party addons
description: Build the independent addons and understand their integration and lifecycle tests.
section: contributors
permalink: /ADDON-CONTRIBUTING/
---

# Maintain first-party addons

Create, Sable and Propulsion are independent Gradle builds under `addons/`. Their integration code, Guest declarations,
ABI locks and tests belong to the owning addon. Use the [all-addon stand]({{ '/DEV-STAND/' | relative_url }}) to verify
behavior with the complete pinned physics runtime. For a new third-party mod, start with the
[public addon SDK]({{ '/ADDON-DEVELOPMENT/' | relative_url }}).

## Create

Run from `addons/create` with Gradle on JDK 25 and a Java 21 toolchain:

```sh
./gradlew-sandbox-dev-parallel-summary check buildProductionJar
./gradlew-sandbox-dev-parallel runGameTestServer
```

The standalone GameTest server uses real kinetic devices and boilers. The production archive checks reject upstream,
base-mod and GameTest implementation classes. Player-facing behavior lives in the [Create guide]({{ '/CREATE/' | relative_url }}).

## Sable

The snapshot adapter is request-only: no background feed, subscriptions or cached body registry. Membership is resolved
for each request. The SDK validates bounded immutable record shapes; Rust materializes the reply in budgeted slices
without invoking Guest constructors. Structured responses use Runtime ABI 1.13 and bundled native C ABI 20.

### Build and test

Run from `addons/sable`, with Gradle on JDK 25 and a Java 21 toolchain available:

```sh
./gradlew-sandbox-dev-parallel-summary check
./gradlew-sandbox-dev-parallel runGameTestServer
./gradlew-sandbox-dev-parallel runClient
```

For joint Create/Sable runs, use the [all-addon development stand]({{ '/DEV-STAND/' | relative_url }}). It is a development stand and produces no umbrella mod.
The lifecycle GameTest compiles and runs a real Guest program before assembly, on the construction and after return
into the world. It checks typed fields, unavailable-operation exceptions, ComputerId retention, runtime replacement
and persisted file contents. Full disassembly, split and merge scenarios need separate coverage.

The pinned Sable JAR embeds Companion, Rapier and Veil; Veil embeds additional libraries. The build extracts those
exact versions for the development classpath. These extracted files are not copied into this addon archive.

## Propulsion

A narrowly scoped mixin marks saves of engines controlled by Compukters and clears unowned digital input/mode on full
NBT loading, including assembly copies. Configuration survives; live program ownership does not transfer. Ordinary
uncontrolled Propulsion and ComputerCraft saves and client update packets retain their upstream behavior.

`state()` uses the adapter's configured kN conversion. In the pinned 1.1.5 binary, `getTargetThrustNewtons()` actually
returns configured kN; its method name is not a units contract. Structured replies use Runtime ABI 1.13 / native C ABI
20. Existing ordinary-engine operation IDs are 0..4, vector operations are 5..11, and `mount()` is ID 12 in capability
version 1. Changing a release's `x.y` version does not automatically change these ABI locks.

### Build and test

Run from `addons/propulsion` with Gradle on JDK 25 and a Java 21 toolchain:

```sh
./gradlew-sandbox-dev-parallel-summary check
./gradlew-sandbox-dev-parallel runGameTestServer
```

For combined Create/Sable/Propulsion and Aeronautics runs, use the [all-addon development stand]({{ '/DEV-STAND/' | relative_url }}).
The standalone build extracts the pinned upstream Sable libraries for Loom; these are not bundled into this addon.

### Lifecycle evidence

Standalone `check` verifies the archive and both ordinary and vector GameTest sources. Real Guest programs exercise
exclusive ownership, invalid inputs, typed observations, cable loss, completion, explicit close, tiny input clearing,
computer removal, NBT copies and actual Sable assembly. Vector tests also preserve live redstone steering signals and
client packets. The shared dev stand composes these scenarios with Create and Sable tests and pinned Aeronautics.
Mount observations are checked in the ordinary world and on an assembled four-engine Sable platform with rotated
peripheral names, including neutral force directions and read-only ownership.
Interactive flight under changing load remains a manual scenario; no additional VM or Propulsion ticks are introduced.

## Version and compatibility checks

The shared [addon versioning policy]({{ '/ADDON-DEVELOPMENT/' | relative_url }}#first-party-addon-versions) keeps the
addon's API line independent of the Compukters target line. Each standalone `check` validates the remapped production
filename, loader version and bounded Compukters dependency through `verifyAddonVersioning`. The workspace SDK and
platform bundle are rebuilt from the same checkout through included-build dependencies.

## Checkpoint ownership

`ProgramAddonHost` and generated `AddonHostHandler` bindings expose bounded `checkpoint()` and
`restoreCheckpoint(state)` methods. Capture must explicitly opt in; stateless handlers return an empty byte array.
A stateful handler serializes logical tokens and resource descriptions, without world objects, callbacks, native
pointers or current machine epochs. Generated bindings frame each handler through `AddonCheckpointCodec` within
1 MiB. `ResourceCheckpointWriter`/`ResourceCheckpointReader` provide bounded scalar, byte and UTF-8 fields.

Capture runs on the server owner during unload, after the computer may already have disappeared from the world's
block-entity table. Preserve identities of previously issued resources rather than invalidating them solely because
that owner detached. Restoration must resolve and validate the current device instance, reachability and exclusivity
before reacquiring anything. Failed rebinding releases partial resources, keeps execution parked and retains the
checkpoint for explicit recovery. Core process-scoped hosts recreate independent live program scopes before activation.
The Sable observation handler is stateless; Create and Propulsion stateful hosts still need resource codecs.
