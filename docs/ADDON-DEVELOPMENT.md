---
layout: default
title: Addon development
description: Expose a typed Guest Kotlin API from an independent NeoForge mod.
permalink: /ADDON-DEVELOPMENT/
---

# Addon development

A Compukters addon is an ordinary, independently installed NeoForge mod. It owns its Minecraft integration, Guest
Kotlin declarations and generated `.cagb` bundle. Compukters reads that data bundle when the addon registers it, but
the base mod never links to or packages the addon implementation.

The public addon SDK uses version 0.3.0 independently of Compukters 0.5.0. The SDK has its own
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
own or invoke its tasks. The Create addon also has its own release version, independent of both the SDK and the base
mod.

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
    id("ru.lazyhat.compukters.addon") version "0.3.0"
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
