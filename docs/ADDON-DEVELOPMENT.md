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

The public addon SDK first ships as version 0.1.0 with Compukters 0.5.0 for Minecraft 1.21.1. The SDK has its own
compatibility version: Compukters releases do not require addon authors to update unless the public addon boundary
changes. All artifacts belonging to one SDK release share that SDK version:

| Coordinate | Build role |
| --- | --- |
| `ru.lazyhat.compukters:compukters-addon-gradle-plugin` | Public plugin implementation for `ru.lazyhat.compukters.addon` |
| `ru.lazyhat.compukters:compukters-addon-tooling` | Isolated Guest API compiler invoked by the plugin |
| `ru.lazyhat.compukters:compukters-guest-platform` | Canonical base platform bundle with extension `cpb` |
| `ru.lazyhat.compukters:compukters-addon-api-neoforge-1.21.1` | Compile-only host and registration API supplied by the installed base mod at runtime |

Released coordinates are intended to resolve from Maven Central. Before SDK version 0.1.0 is published, a Compukters
checkout can publish the same SDK coordinates to Maven Local with:

```shell
./gradlew publishAddonSdkToMavenLocal
```

The standalone addon checks `mavenLocal()` before public repositories, so direct Gradle runs and IDE imports resolve
the development SDK after that one bootstrap command. `stageAddonSdkMavenRepository` remains available for isolated
builds and TestKit verification that need an explicit repository under `build/repositories/addon-sdk`.

The first-party Create addon under `addons/create` is itself a separate Gradle root and serves as the complete example.
It contains no project dependency, included build, shared source directory, or path back into the Compukters build,
and owns the Gradle wrapper that pins its build toolchain.
From the Compukters root, `verifyCreateAddon`, `buildCreateAddon`, and `runCreateAddonClient` first publish the selected
SDK version to Maven Local and then invoke that independent build. The Create addon also has its own release version,
independent of both the SDK and the base mod.

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
    id("ru.lazyhat.compukters.addon") version "0.1.0"
    // Apply and configure Loom/NeoForge as usual for the target mod.
}

compuktersAddon {
    addon.set("example")
    module.set("sensors")
    dependencies.set(listOf("stdlib:core"))
    capability("sensors")
}
```

The plugin adds the API belonging to the selected SDK release as `compileOnly`. Do not shade or Jar-in-Jar that API
into the addon: the separately installed Compukters mod supplies those classes. Declare Compukters as a required
dependency in the addon's `neoforge.mods.toml`, and add the ordinary Compukters development mod to the run configuration
used by your Loom setup.

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

For one capability the SDK infers the single external binding owner. If a Guest module exposes several capabilities,
identify each owner explicitly:

```kotlin
compuktersAddon {
    addon.set("example")
    module.set("devices")
    capability("sensors") {
        bindingOwner.set("example.devices.SensorBindings")
    }
    capability("motors") {
        bindingOwner.set("example.devices.MotorBindings")
    }
}
```

The build derives operation signatures, asynchronous host operations, stable numeric selectors, capability schemas,
bindings and request decoding. Addon source never contains those wire details.

## Implement and register the host

`assembleCompuktersAddon` generates a typed contract in the Guest module package. For the example above it provides
`SensorsCapabilityHandler` and `SensorsAddonContract`:

```kotlin
CompuktersAddonRegistry.register(SensorsAddonContract.guestApi(MyAddon::class.java)) { level, position, state ->
    SensorsAddonContract.host(
        object : SensorsCapabilityHandler {
            override fun temperature(): AddonCallResult<Float> = addonCompleted(readTemperature(level, position))
        },
    )
}
```

Handlers execute through the bounded server-side addon boundary. Return `addonCompleted(value)` for an immediate
result, `addonFailed(kind, detail)` for a descriptive Guest failure, or `addonPending { ... }` when the world operation
must wait. The generated host validates and decodes requests and routes completions back to the exact suspended Guest
task.

Call registration from your own NeoForge mod initialization. The generated `guestApi(...)` loader reads the bundle
from the addon's JAR; the plugin automatically packages it as
`META-INF/compukters/addons/<addon>-<module>.cagb`.

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
