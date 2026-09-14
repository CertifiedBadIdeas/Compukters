---
layout: default
title: Create kinetics
description: Read and control adjacent Create kinetic devices from Guest Kotlin.
permalink: /CREATE-KINETICS/
---

# Create kinetics

Compukters can expose adjacent Create kinetic devices directly to Guest Kotlin. This optional integration is available
on **Minecraft 1.21.1** with **Create 6.0.10 through 6.0.x**. Compukters still starts normally when Create is absent;
the `create:kinetics` platform module is then unavailable to projects.

## Enable the project module

Choose a `Kinetics` completion in the attached IDE to add its import and enable the module automatically, or add the
Create module to the project's `compukter.toml` manually:

```toml
format = 1
name = "kinetic-monitor"

[modules]
create = { kinetics = 1 }
```

The IDE, analysis worker, and compiler all use the attached server's target profile. A project that requests this module
therefore resolves only when the target actually has the compatible Create integration loaded.

## Sides and devices

Sides are relative to the front of the computer, just like the redstone API:

```kotlin
import create.kinetics.Kinetics

fun main() {
    val input = Kinetics.left.speedometer()
    val load = Kinetics.bottom.stressometer()
    val controller = Kinetics.top.rotationController()

    println(input.speed())
    println(load.stress())
    println(load.capacity())
    println(controller.setTargetSpeed(128))
}
```

`front`, `back`, `left`, `right`, `top`, and `bottom` inspect exactly one adjacent loaded block. They never scan the
world or force a chunk to load. The requested block must be the matching Create block entity:

| Accessor | Adjacent Create device | Operations |
| --- | --- | --- |
| `speedometer()` | Speedometer | `speed(): Float`, `awaitSpeedChange(): Float` |
| `stressometer()` | Stressometer | `stress(): Float`, `capacity(): Float`, `awaitChange(): Unit` |
| `rotationController()` | Rotation Speed Controller | `targetSpeed(): Int`, `setTargetSpeed(Int): Int` |

Speed, stress, and capacity retain Create's `Float` values without integer rounding. Speeds are signed. A rotation
controller applies Create's own configured bounds, and `setTargetSpeed` returns the value that was actually accepted.
If Create exposes `NaN`, positive or negative infinity, or signed zero, the same binary32 value reaches Guest Kotlin;
the integration does not sanitize, widen, or convert it to fixed point. Change waits compare the observed binary32
value, including the distinction between positive and negative zero.

`awaitSpeedChange()` suspends until the observed speed changes and returns the new value. `awaitChange()` suspends until
either stress or capacity changes. These waits do not busy-poll in Guest code.

## Lifetime and limits

A device accessor creates a handle for that exact adjacent block entity. Replacing the block invalidates the old
handle; it never silently reconnects to the replacement. Missing, mismatched, unloaded, removed, or stale devices fail
the Guest operation deterministically, and the terminal process diagnostic identifies which condition prevented the
operation.

Each running computer may retain at most 64 Create device handles and 64 pending Create waits. Host requests and
completions cross the same bounded asynchronous actor path as other world-facing computer operations and execute on the
server thread where Minecraft and Create state may safely be accessed.
