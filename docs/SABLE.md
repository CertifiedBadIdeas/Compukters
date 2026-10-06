---
layout: default
title: Sable addon
description: Read construction pose and velocity from Guest Kotlin.
section: players
permalink: /SABLE/
---

# Compukters: Sable

Independent observation addon for Minecraft 1.21.1, NeoForge 21.1.252 and Sable 2.0.5 (Modrinth U678xqle).
Install it alongside Compukters and Sable. Main Compukters has no Sable dependency; the Create addon remains independent.

Addon releases use `x.y` (API compatibility line and compatible update), separately from the Compukters target line.
For example, `compukters-sable-1.21.1-neoforge-0.5-1.0.jar` targets Compukters 0.5 and is addon version 1.0. Exact minimum
versions are enforced by loader metadata. See [addon versioning]({{ '/ADDON-DEVELOPMENT/' | relative_url }}#first-party-addon-versions).

## Guest API

Add `"sable"` to the project's addon list in `compukter.toml`, then compile against the server's installed API:

```kotlin
import sable.physics.Physics

fun main() {
    try {
        val snapshot = Physics.snapshot()
        println(snapshot.constructionId)
        println(snapshot.position.y)
        println(snapshot.linearVelocity.y)
    } catch (failure: IllegalStateException) {
        println("Computer is not on an available construction")
    }
}
```

`Physics.snapshot()` makes one asynchronous host request and returns a typed immutable `PhysicsSnapshot`.
It contains `constructionId` (UUID text), `dimension` (Minecraft dimension ID), `gameTick` (Long), `paused` (Boolean),
`position`, `orientation`, `scale`, `rotationPoint`, `linearVelocity` and `angularVelocity`.
Vectors have Double x/y/z; orientation has Double x/y/z/w. The snapshot is a copy: later physics updates or removal
of the construction do not modify it. Membership is resolved on each request; no body handle is retained.
A world computer or unavailable construction yields a catchable IllegalStateException.

## Coordinates and timing

Pose fields copy Sable's logical pose. `position` is the world position of `rotationPoint`, which is expressed in the
construction's plot coordinates. `orientation` rotates from plot axes to world axes; `scale` is dimensionless.
For a plot-space point `p`, the world transform is:

```text
world = position + orientation.rotate(scale * (p - rotationPoint))
```

Positions use Minecraft block units (Sable treats one block as one metre). Solver `linearVelocity` is the body's
global velocity in metres per second, not velocity at the computer. `angularVelocity` is global angular velocity in
radians per second. These values use Sable's physics handle, rather than the pose-difference velocity fields.

`gameTick` is the server world's observation tick, not a physics substep counter or elapsed simulation time.
`paused` reports the physics system's paused state. Logical pose and solver velocities are copied during one
server-thread request, without advancing physics. Paused physics can retain earlier values. The VM keeps its existing
world-tick cadence and budget; snapshot requests add neither substep VM turns nor synchronous physics waits.

No subscriptions, background sampler, cached feed or body registry are installed. Without a request, the addon does
no snapshot work. The SDK validates record shapes and the VM materializes the copied reply in budgeted slices,
without invoking Guest constructors during resume. This requires Runtime ABI 1.13 and native C ABI 20; rebuild addon
bundles and Guest programs with the current workspace tooling.

## Development

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
