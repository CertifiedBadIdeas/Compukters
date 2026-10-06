---
layout: default
title: Addon development
description: Expose a typed Guest Kotlin API from an independent NeoForge mod.
permalink: /ADDON-DEVELOPMENT/
section: developers
---

# Addon development

A Compukters addon is an ordinary, independently installed NeoForge mod. It owns its Minecraft integration, Guest
Kotlin declarations and generated `.cagb` bundle. Compukters reads that data bundle when the addon registers it, but
the base mod never links to or packages the addon implementation.

The public addon SDK uses version 0.5.0 independently of Compukters 0.5.0. The SDK has its own
compatibility version: Compukters releases do not require addon authors to update unless the public addon boundary
changes. All artifacts belonging to one SDK release share that SDK version:

| Coordinate | Build role |
| --- | --- |
| `ru.lazyhat.compukters:compukters-addon-gradle-plugin` | Public plugin implementation for `ru.lazyhat.compukters.addon` |
| `ru.lazyhat.compukters:compukters-addon-tooling` | Isolated Guest API compiler invoked by the plugin |
| `ru.lazyhat.compukters:compukters-guest-platform` | Canonical base platform bundle with extension `cpb` |
| `ru.lazyhat.compukters:compukters-addon-api` | Minecraft-independent host contracts, result types, and Guest API models |
| `ru.lazyhat.compukters:compukters-addon-neoforge-1.21.1` | Thin compile-only registration adapter for NeoForge 1.21.1 |
| `ru.lazyhat.compukters:compukters-addon-neoforge-26.1.2` | Thin compile-only registration adapter for NeoForge 26.1.2 |

Released coordinates are intended to resolve from Maven Central. Before an SDK version is published, a Compukters
checkout can publish the same SDK coordinates to Maven Local with:

```shell
./gradlew :addon-gradle-plugin:publishAddonSdkToMavenLocal
```

The common API and target adapters are compile-only dependencies and carry the SDK version. Isolated TestKit
verification creates its own temporary Maven layout directly from the built SDK artifacts.

The first-party Create addon under `addons/create` is itself a separate Gradle root and serves as the complete example.
It owns its Gradle wrapper and includes the adjacent Compukters checkout as a composite build for local co-development.
Public SDK coordinates remain in the addon build, and explicit substitutions select the matching local projects. The
runtime uses the adjacent checkout's self-contained development JAR and carries its included-build task dependency, so
Gradle rebuilds it before `runClient` without publishing it to Maven Local or passing it through Loom's mod remap cache.
After publishing the Gradle plugin and tooling SDK, run `check`,
`buildProductionJar`, or `runClient` from `addons/create`. The Compukters root remains unaware of the addon and does not
own or invoke its tasks. First-party addons use the two-part release policy below, independently of the SDK version.

## First-party addon versions

Create, Sable and Propulsion use `x.y` versions. `x` identifies the addon's public API compatibility line; increment it
and reset `y` to zero for an incompatible public API change. Increment `y` for backward-compatible updates, including
fixes and additions. The number is independent of the Compukters version and is not three-part SemVer. Moving to a new
Compukters target line does not reset the addon version or require an API-line bump by itself.

The production archive also names the Compukters target line:

```text
compukters-create-1.21.1-neoforge-0.5-2.0.jar
```

Here `0.5` is the target Compukters major/minor line and `2.0` is the Create addon's own API/update version. The
`addonVersion` property in each first-party addon's `gradle.properties` owns its `x.y` value. The shared
`addons/gradle/addon-versioning.gradle.kts` derives the target line from the adjacent Compukters checkout and expands
the required base-mod dependency. The lower bound is the workspace version used to build the addon; the upper bound
is the next minor line. A build against Compukters `0.5.0` therefore requires `[0.5.0,0.6.0)`. A build against `0.5.3`
requires `[0.5.3,0.6.0)` even though its filename still contains `0.5`; use the declared dependency for the exact minimum.

An addon depending on another addon's API line 1 can declare a NeoForge dependency range `[1.0,2.0)`, or `[1.2,2.0)` if
it needs functionality introduced in update 1.2. This accepts later compatible updates while rejecting API line 2.
Keep capability ABI versions and ABI locks explicit; changing a release number does not automatically migrate them.
Guest build locks still identify exact addon bundle versions and content hashes and may require re-resolution/rebuild
when an addon is updated. A loader dependency range does not make previously compiled Guest artifacts interchangeable.

This policy covers the three first-party addons. Independent SDK users can retain their own mod versioning policy.
Stable development and GameTest archive names are retained for the workspace's included-build composition. Each addon
`check` verifies its production filename, packaged version and Compukters dependency range through
`verifyAddonVersioning`.

## Apply the plugin

Keep the normal Kotlin, Loom and NeoForge setup of your mod. Add Maven Central to both plugin and dependency
resolution, then apply the matching Compukters plugin version:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.10"
    id("ru.lazyhat.compukters.addon") version "0.5.0"
    // Apply and configure Loom/NeoForge as usual for the target mod.
}

compuktersAddon {
    register("example")
}
```

The Guest API version defaults to the Gradle project's `version`; pass `version = "..."` only when those versions
intentionally differ. One registered addon produces one atomic Guest API bundle. Its packages can still be organized
freely beneath the addon namespace, while base-platform dependencies and capability wiring remain SDK internals.

The plugin adds the Minecraft-independent API and the NeoForge 1.21.1 adapter belonging to the selected SDK release as
`compileOnly`. A build targeting another supported version can set `adapterApiCoordinate` to the matching coordinate.
Do not shade or Jar-in-Jar either artifact into the addon: the separately installed Compukters mod supplies those
classes. Declare Compukters as a required dependency in the addon's `neoforge.mods.toml`, and add an ordinary
Compukters development mod with its own product version to the run configuration used by your Loom setup.

## Write the Guest API

Put declarations beneath `src/compuktersAddon/kotlin`. The first directory and Kotlin package must belong to the addon
namespace. Write ordinary supported Guest Kotlin and keep only the host boundary `external`:

```kotlin
// src/compuktersAddon/kotlin/example/sensors/Sensors.kt
package example.sensors

public object Sensors {
    public fun temperature(): Float = SensorBindings.temperature()
}

private object SensorBindings {
    external fun temperature(): Float
}
```

The build derives operation signatures, asynchronous host operations, stable numeric selectors, capability schemas,
bindings and request decoding. Addon source never contains those wire details.

## Implement and register the host

`assembleCompuktersAddon` generates a typed contract in the addon package. For the example above it provides
`ExampleCapabilityHandler` and `ExampleAddonContract`:

```kotlin
CompuktersAddonRegistry.register(ExampleAddonContract.guestApi(MyAddon::class.java)) { computer ->
    ExampleAddonContract.host(
        object : ExampleCapabilityHandler {
            override fun temperature(): AddonCallResult<Float> =
                addonCompleted(readTemperature(computer.level, computer.position))
        },
    )
}
```

`CompuktersComputerContext` exposes the server level, computer position, and `adjacentDirection(side)` mapping without
requiring the addon to link against Compukters block implementation classes.

SDK 0.3.0 also lets an addon describe logical devices that a Compukters cable can touch. Pass a
`CompuktersPeripheralProvider` as the third registration argument. It receives the loaded server level, contacted
block position, and the face of that block touched by the cable, then returns a canonical anchor and an optional
bounded provider key:

```kotlin
CompuktersAddonRegistry.register(
    ExampleAddonContract.guestApi(MyAddon::class.java),
    CompuktersAddonHostFactory { computer -> createExampleHost(computer) },
    CompuktersPeripheralProvider { contact ->
        resolveExampleMultiblock(contact)?.let { device ->
            CompuktersPeripheralDevice(device.controllerPosition, device.portKey)
        }
    },
)
```

Return the same canonical identity for every supported part of one multiblock. Compukters supplies the addon ID,
dimension, and lifecycle checks around that identity; the provider key should distinguish logical devices sharing one
anchor and must contain at most 128 printable ASCII characters. Existing addons may keep the two-argument
registration call and side-based discovery unchanged.

SDK 0.5.0 uses platform ABI 3 and standalone module format 5. Rebuild addon bundles against its base platform;
older encoded bundles are rejected. The SDK retains `CompuktersComputerContext.isPeripheralReachable(device)` for validating retained handles against the
computer's current loaded cable component. The check uses the canonical provider identity rather than the device name,
so renaming the same reachable device does not redirect or invalidate its handle. Addons should latch the first
`false` result when a handle must never revive after disconnection.

SDK 0.5.0 adds a fourth registration argument, `List<CompuktersPeripheralContract<*>>`, for typed providers.
Each contract has a unique namespaced id, a logical device key, and a resolver returning a
`CompuktersPeripheralEndpoint`. Its `identity` must be the pinned physical instance; `valid()` must reject
removal, replacement and unloading. The resolver receives a `CompuktersPeripheralLocation` with the canonical
anchor and a latching `isReachable()` check. One logical device may expose several contracts.

Guest wrappers can be scalar value classes implementing `compukter.peripheral.Peripheral`; their companions inherit
`TypedPeripheralProvider<Wrapper>("addon:contract")` and implement the protected `wrap(handle)` method.
The base implements typed `first`, `firstOrNull`, `filter`, `all`, `at`, `atOrNull`, `named`, and `namedOrNull`.
It owns bounded snapshots and exact-instance handles, with cleanup after early return or predicate failure.
Addon capabilities continue to own device operations; they accept the base handle as an `Int`.

In a host handler, use `computer.peripheral(contract, handle)` with the same descriptor object used at registration.
Forward `CompuktersPeripheralAccessException.kind` and `.message` through `addonFailed` rather than converting
stale or wrong-type handles into optional absence. Pending operations must revalidate access while polling.
The base checks descriptor identity, expected contract and latched reachability before exposing a typed endpoint.
Use `computer.peripheralAt(contract, side)` and `peripheralNamed(contract, name)` when adapting legacy
acquisition operations: they issue the same tokens as provider discovery and return zero only for absence.
`closePeripheral(contract, handle)` invalidates every alias of that token; a later acquisition receives a fresh token.
Release any addon-owned control lease when closing or resetting your host.
Provider roles are inferred from the interface in both source and admitted addon metadata, so no annotation is needed.
Value classes retain scalar direct-call signatures and use canonical managed wrappers in interface, nullable and
collection contexts. Implementing `Peripheral` does not require converting a device to an ordinary class.
Create and Propulsion use `2.0` because their earlier acquisition helpers and separate side types are removed.
Acquire devices through typed companion providers and `compukter.peripheral.Side`.
Changing a published wrapper's scalar/reference representation is a binary API change: increment the addon's `x`
compatibility line and rebuild dependents. Exact Guest bundle hashes still require re-resolution after an update.

Legacy registration overloads remain available for addons using their existing discovery helpers.

Handlers execute through the bounded server-side addon boundary. Return `addonCompleted(value)` for an immediate
result, `addonFailed(kind, detail)` for a descriptive Guest failure, or `addonPending { ... }` when the world operation
must wait. The generated host validates and decodes requests and routes completions back to the exact suspended Guest
task.

Call registration from your own NeoForge mod initialization. The generated `guestApi(...)` loader reads the bundle
from the addon's JAR; the plugin automatically packages it as `META-INF/compukters/addons/<addon>.cagb`.

## Preserve the ABI lock

Operation selectors are an artifact/runtime ABI even though addon authors never choose their numbers. Check in
`src/compuktersAddon/addon.lock`. Create it, or update it after an intentional Guest API change, with:

```shell
./gradlew updateCompuktersAddonAbiLock
```

Review lock changes like a public API change. Reordering declarations preserves selectors, removed operations remain
as tombstones, and new operations append after all existing selectors. Ordinary builds run
`assembleCompuktersAddon` and fail when the checked-in lock is missing or stale.

The project's ordinary `jar`, `check` and Kotlin compilation tasks depend on the generated bundle and host contract.
The consumable `compuktersAddonBundle` configuration is also available to custom packaging or verification tasks.

## Joint development runs

[`addons/dev`](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/addons/dev) composes the main mod and the
independent Create and Sable builds, with Aeronautics 1.3.1 (including Simulated/Offroad) and Propulsion: Simulated 1.1.5
on the runtime classpath. From that directory, run `./gradlew-sandbox-dev-parallel-summary verifyAddons`
for both addon checks, or `./gradlew-sandbox-dev-parallel runGameTestServer` for their shared real NeoForge scenarios.
`runClient` and `runServer` use the same upstream dependency pins and ordinary development archives. This build
produces no distributable umbrella mod; adding another addon does not introduce a dependency between existing addons.


## Structured host results

Current workspace tooling accepts an asynchronous binding returning a public immutable data record in the addon's
namespace. Declare non-null `val` fields containing Int, Long, Float, Double, Boolean, Char, String or other supported
records. Records must have no type parameters, superclass, custom body, constructor defaults or initialization logic.
Arrays, nullable fields, record arguments and cyclic record graphs are outside this initial contract.

The SDK generates a JVM mirror DTO, an ordered nominal schema and its typed response encoder. Authors return the
mirror through `AddonCallResult`; no reflection, handwritten operation numbers or Guest constructors are involved.
The ABI lock records the complete nested shape, so changing field order, name or type requires a reviewed ABI update.
Record bundles use CAGB 3 / lock format 2; scalar-only contracts retain their earlier encoding. Programs returning
records require Runtime ABI 1.13; runtime transports require C ABI 20.

Limits are 8 nesting levels, 32 record/String nodes, 64 expanded fields, 4096 aggregate UTF-16 code units and a 64 KiB
response. The native boundary validates and copies caller-owned responses. Normal VM advancement allocates rooted
objects and Strings in slices, then publishes the root atomically. The host does not execute Guest construction code.
See [the Sable addon](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/addons/sable) for a complete nested record
API and its real assembly/return GameTest.

Addon hosts are created separately for each live Guest program when it first calls an addon. A suspended parent
retains its host and devices while a child runs. Returning or failing from the child closes only the child's hosts;
stopping or removing the computer closes every host. Request IDs are unique for the lifetime of the native computer,
so a late completion cannot bind to a newer program. Host factories must return fresh instances and tolerate `close()`
without prior requests. The main mod delivers these lifecycle transitions on the server thread.
