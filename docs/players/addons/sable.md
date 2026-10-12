---
layout: default
title: Sable addon
description: Read construction physics and use peripheral networks on Sable constructions.
section: players
permalink: /SABLE/
---

# Sable addon

Read a construction's position, orientation, velocity and mass properties from a Kotlin program running on that construction.
The returned snapshot is immutable, so your program can keep it while the construction continues moving.

## Install

Install Compukters: Sable alongside the base mod and upstream Sable on client and server:

| Component | Target |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.252 or newer |
| Sable | 2.0.6 (Modrinth fg9dTRz9) |

The base mod works without Sable. This addon does not require Compukters: Create.

Addon releases use `x.y` (API compatibility line and compatible update), separately from the Compukters target line.
For example, `compukters-sable-1.21.1-neoforge-0.6-1.2.jar` targets Compukters 0.6 and is addon version 1.2. Exact minimum
versions are enforced by loader metadata. See [addon versioning]({{ '/ADDON-DEVELOPMENT/' | relative_url }}#first-party-addon-versions).

## Peripheral networks

Use the configurator's network mode to bind computers and peripherals exactly as in the
[peripheral guide]({{ '/PERIPHERALS/' | relative_url }}). You can bind devices before assembly or directly on a construction.
Networks can include shore devices and devices on separate constructions. Guest calls such as
`GraphicalDisplay.named("screen")` and Create peripheral lookup remain the same; using networks does not require adding
`"sable"` to `compukter.toml`.

The configurable 64-block default radius is measured from each computer to the peripheral's current world position,
including construction rotation and scale. Both devices must be loaded and in the same dimension. Leaving range makes
the device unavailable without removing its binding; a stale Guest handle must be acquired again.

Assembly and moving blocks back to the world retain network membership and names. Moving a complete composed display
also retains its screen identity and published image. If only part of a screen moves, it becomes separate panels:
each keeps its part of the image and network, and the first panel keeps the name. Private drawing frames are discarded.
The configurator's outlines and network inspection use the current construction positions.

## Read your first snapshot

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

## Mass properties

Sable addon 1.2 adds `Physics.massProperties()`, a separate asynchronous request returning immutable
`PhysicsMassProperties`: `constructionId`, `dimension`, `gameTick`, `mass`, `centerOfMass` and `inertiaTensor`.

```kotlin
import sable.physics.Physics

fun main() {
    val properties = Physics.massProperties()
    println("Mass: " + properties.mass)
    println("Plot center X: " + properties.centerOfMass.x)
    println("Inertia about plot Y: " + properties.inertiaTensor.yy)
}
```

The values copy Sable's **merged** mass tracker, including kinematic contraptions accounted for by Sable,
rather than counting only blocks in the construction's own plot. `centerOfMass` uses plot coordinates;
`inertiaTensor` is expressed in plot axes about that center, rather than in world axes or about the computer.
The tensor exposes all nine Double components `xx`, `xy`, `xz`, `yx`, `yy`, `yz`, `zx`, `zy`, `zz`.
The first letter is the row and the second the column: its X output is `xx * x + xy * y + xz * z`.
This naming avoids JOML's column-first matrix accessor convention.

Units are copied without conversion: Sable 2.0.6 documents mass as `kpg` and inertia as `kpg * m²` in its
[MassData contract](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.6-neoforge/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MassData.java).
The center uses Minecraft block units (one metre per block). No conversion to kilograms is implied.

The request copies the last mass data published by upstream, without rebuilding the tracker or advancing physics.
`gameTick` records the request's server tick, not the last mass-recomputation tick. A retained result stays unchanged
when the construction changes or disappears. Separate `snapshot()` and `massProperties()` calls may observe different
ticks; they do not form an atomic pair. Use the pose transform below to transform a plot-space center into world space.
A world computer, removed construction, uninitialized/nonpositive mass, or nonfinite mass/center/tensor yields a
catchable `IllegalStateException`. The existing pose snapshot remains usable independently of mass initialization.

## Coordinates and timing

Pose fields copy Sable's logical pose. `position` is the world position of `rotationPoint`, which is expressed in the
construction's plot coordinates. `orientation` rotates from plot axes to world axes; `scale` is dimensionless.
For a plot-space point `p`, the world transform is:

```text
world = position + orientation.rotate(scale * (p - rotationPoint))
```

### Orientation conventions and Guest rotations

The adapter copies JOML quaternion components in **x, y, z, w** order: x/y/z form the vector part,
w is the scalar part. It does not reorder components or conjugate the pose orientation.
JOML's unit-quaternion transform uses the same active column-vector convention as Guest Rotation3d;
see [Quaterniond transformUnit](https://joml-ci.github.io/JOML/apidocs/org/joml/Quaterniondc.html#transformUnit(double,double,double,org.joml.Vector3d)).
A positive quarter turn around plot +Z maps plot +X to world +Y when the axes initially coincide.
The inverse rotation maps world directions back to plot axes; scale and pivot are separate operations.

The current Sable snapshot still exposes PhysicsRotation components. With stdlib:core 1.19, convert that
owned observation explicitly to a validated spatial rotation:

```kotlin
import kotlin.math.Quaterniond
import kotlin.math.Rotation3d
import kotlin.math.Vector3d

val q = snapshot.orientation
val rotation = Rotation3d.fromQuaternion(Quaterniond(q.x, q.y, q.z, q.w))
val worldDirection = rotation.transform(Vector3d(1.0, 0.0, 0.0))
val plotDirection = rotation.inverse().transform(worldDirection)
```

The factory normalizes finite nonzero input and rejects zero/non-finite components. Rotation unit length
is approximate within Double rounding. This conversion does not change the snapshot's observation tick,
apply scale/translation, or silently invert the quaternion. Use non-identity rotations around distinct axes
when testing an adapter; an identity-only test cannot detect a swapped component or reversed rotation.
The planned SDK/Sable value-response migration is separate from this explicit conversion.

Positions use Minecraft block units (Sable treats one block as one metre). Solver `linearVelocity` is the body's
global velocity in metres per second, not velocity at the computer. `angularVelocity` is global angular velocity in
radians per second. These values use Sable's physics handle, rather than the pose-difference velocity fields.

`gameTick` is the server world's observation tick, not a physics substep counter or elapsed simulation time.
`paused` reports the physics system's paused state. Logical pose and solver velocities are copied during one
server-thread request, without advancing physics. Paused physics can retain earlier values. The VM keeps its existing
world-tick cadence and budget; snapshot requests add neither substep VM turns nor synchronous physics waits.

For the API's exact signatures, see the [Sable reference]({{ '/guest-api/sable/' | relative_url }}). Build commands,
integration ownership and test coverage live in [Maintain first-party addons]({{ '/ADDON-CONTRIBUTING/' | relative_url }}#sable).

## Hibernation

A computer keeps its execution when assembled, when its Sable construction unloads and reloads, and when moved back
into the world. Retained `PhysicsSnapshot` values remain copies; each new `Physics.snapshot()` resolves the computer's
current construction. Sable physics continues during restoration. In the live-physics GameTests, one computer on a two-block construction
continued and queried the same construction in 35 ms; both computers on a 29-block construction continued in 68 ms.
These unthrottled GameTest measurements exclude cold compilation; larger states and loaded servers may take longer.
