---
layout: default
title: Aeronautics addon
section: players
permalink: /AERONAUTICS/
---

# Aeronautics addon

The optional Compukters: Aeronautics 1.2 addon targets Minecraft 1.21.1, Create 6.0.10
and Aeronautics/Simulated 1.3.2. Enable `"aeronautics"` in the Guest project and bind/name
native blocks through the configurator. The base mod does not require this addon.

`aeronautics.servo.Servo` controls finite physical swivel-bearing movement. It requires
real kinetic power and an assembled native bearing; it never creates a virtual body.
`moveTo(degrees, speed)`, `rotateBy(degrees, speed)`, `stop()`, `state()` and
`awaitStopped(timeoutTicks)` operate through the program-owned actuator lease.

Native docking remains controlled by redstone. Its state can be observed without extending,
locking, retracting or transferring any fuel:

```kotlin
import aeronautics.docking.DockingConnector
fun main() {
    val dock = DockingConnector.named("fuel-dock-left")
    println(dock.state().locked)
    println(dock.fluidState().amountMb)
}
```

`state()` returns server tick and native connected, locked, extended and retracted flags.
A locked connector physically constrains the paired bodies. Wait for actual release before thrust.
`fluidState()` returns server tick, fluid registry ID (empty string for empty stock), own amount
and capacity in mB. This includes liquid retained in its 1000 mB buffer while disconnected;
it does not include the paired ground connector's stock. Sampling never fills or drains a buffer.
Handles remain bound to the exact loaded, reachable block entity; replacement does not inherit them.
