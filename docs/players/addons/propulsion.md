---
layout: default
title: Propulsion addon
description: Control Creative Thrusters and Creative Vector Thrusters from Guest Kotlin.
section: players
permalink: /PROPULSION/
---

# Propulsion addon

Control Creative Thrusters and Creative Vector Thrusters from Kotlin. Use normalized throttle, steer vector nozzles,
and read typed engine state. Install [Compukters: Sable]({{ '/SABLE/' | relative_url }}) separately if your program also
needs construction pose and velocity.

The device classes also support shared typed discovery: `first`, `firstOrNull`, `filter`, `all`,
`at`/`atOrNull`, and `named`/`namedOrNull`. See [typed peripheral discovery]({{ '/PERIPHERALS/' | relative_url }}).
Use these typed providers for every device lookup.

## Install

Install Compukters: Propulsion, the base mod and its upstream runtime on client and server:

| Component | Target |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.252 or newer |
| Create | 6.0.10 through 6.0.x |
| Sable | 2.0.5 |
| Propulsion: Simulated | 1.1.5 (Modrinth H13U56dc) |
| Simulated | 1.3.1, supplied by the Aeronautics 1.3.1 bundle |

The Compukters addon does not require Compukters: Create or Compukters: Sable. Its upstream mods still require the
runtime above, including Simulated even though upstream Propulsion metadata omits that dependency.

Addon releases use independent `x.y` versions. For example, `compukters-propulsion-1.21.1-neoforge-0.5-2.0.jar` is
addon 2.0 for the Compukters 0.5 line. Loader metadata enforces the exact minimum; see the
[addon overview]({{ '/ADDONS/' | relative_url }}) for compatibility and project selection.

## Guest API

Select `addons = ["propulsion"]` in `compukter.toml` (format 3). Place the engine against any face of the computer or
connect them with peripheral cables, and name the engine with the Peripheral Configurator. Ordinary Creative Thruster
multiblock contacts resolve to their controller.

For **Creative Vector Thruster** (`createpropulsion:creative_vector_thruster`):

```kotlin
import propulsion.thrusters.CreativeVectorThruster

fun main() {
    val engine = CreativeVectorThruster.named("main-engine")
    engine.setThrustKn(120.0)
    engine.setVector(0.6, -0.4)
    engine.setThrottle(0.75)
    println(engine.state().currentThrustKn)
    readln()
    engine.close()
}
```

`setVector(x, y)` uses local nozzle coordinates −1..1, not world coordinates or degrees. Fractional targets retain upstream Float precision without
redstone-level rounding; ordinary nozzle smoothing still applies. Digital steering overrides the target while live redstone-link
inputs keep updating; release restores the latest link inputs. `setThrustKn` temporarily overrides the base creative
thrust before throttle and atmosphere, within `state().maxThrustKn`. It does not change the saved scroll-wheel setting.
`clearThrustOverride()` returns that setting while keeping ownership. Close, program completion, cable loss and
computer removal release throttle, steering and custom thrust together. Saves and Sable copies preserve redstone
signals and saved configuration without carrying program commands; client update packets retain visual state.

`mount()` returns `CreativeVectorThrusterMount` without claiming control: `constructionId`, integer
`offsetX/offsetY/offsetZ` from the calling computer's block to the engine's block, and `forceX/forceY/forceZ`
for the neutral (zero-steering) force direction. On a Sable construction these use plot axes, so offsets and
corner assignments survive world translation/rotation. In the ordinary world the ID is empty and axes are world axes.
The engine must share the computer's construction (or both be outside constructions); a different construction
throws IllegalStateException. Reachability, replacement and handle validity use the same checks as `state()`.
Neutral direction describes mounting, not current smoothed nozzle steering; it is a unit axis vector.
Use offsets to assign roll/pitch torque signs instead of treating peripheral names as geometric labels.

`CreativeVectorThrusterState` includes `gameTick`, requested/effective throttle, `thrustKn`, `maxThrustKn`,
`customThrust`, `currentThrustKn`, target/current X/Y coordinates, `startupProgress` and `active`. Both thrust fields
use kN; current output and current vector represent the upstream tick, which may lag commands. Finite input/range
checks apply on both sides; exceeding the server-configured thrust limit throws IllegalStateException.

For the ordinary **Creative Thruster** (`createpropulsion:creative_thruster`):

```kotlin
import propulsion.thrusters.CreativeThruster

fun main() {
    val engine = CreativeThruster.named("main-engine")
    engine.setThrustPercent(50)
    engine.setThrottle(0.75)
    val state = engine.state()
    println(state.configuredThrustKn)
    println(state.currentThrustKn)
    readln() // Keep the program alive while it controls the engine.
    engine.close()
}
```

`setThrustPercent(1..100)` changes the saved engine setting (upstream index 0..99). At full throttle, configured thrust
is this percentage of the server-configured maximum for the current single-block/2x2x2/3x3x3 engine. This setting
persists after the program exits. `setThrottle(Double 0..1)` changes the live normalized digital input; Propulsion
stores Float precision and ignores input changes no greater than 1e-4. The adapter preserves its ordinary startup,
fade, atmosphere and obstruction behavior; requests do not advance physics or add VM turns.

A modifying command claims exclusive program ownership. Acquisition and `state()` are read-only and do not claim it.
Another program can observe the engine but its modifying commands throw IllegalStateException while occupied.
An attached ComputerCraft peripheral prevents a new Compukters writer. Concurrent takeover by other mods is outside
the exclusive Compukters lease contract; cleanup does not overwrite an attached ComputerCraft controller.

Program completion/stop, computer removal, and observed cable loss release the digital input and restore normal
redstone. Cable validation runs on server ticks only for owned engines, including while the program waits for input.
Release also clears the program's stored shutdown envelope before restoring engine settings and immediately
recalculates physical thrust. Unpowered redstone produces no residual program thrust or handoff spike.
Powered redstone may immediately request thrust. `close()` releases control early and invalidates the handle.
Stale/disconnected/replaced/unloaded handles throw IllegalStateException; reacquire by name after reconnecting.
Aliases acquired by the same program for the same engine share one handle, so closing one closes all its aliases.
NaN, infinities, values outside 0..1, and percentages outside 1..100 throw IllegalArgumentException before a command.
The computer admits at most 1,024 live typed peripheral handles across all integrations; handle IDs are not reused.

## State and units

`state()` returns an immutable `CreativeThrusterState` from one server-thread request. It includes world `gameTick`,
requested `throttle`, `effectiveThrottle`, `thrustPercent`, `configuredThrustKn`, `currentThrustKn`, `startupProgress`,
`active`, `startingUp`, `fadingOut`, multiblock `width`, and `unobstructedBlocks`.
Configured and current thrust are kilonewtons; current output converts Propulsion units using the server's conversion
factor. Current thrust is the last output
computed by Propulsion's ordinary tick, so a command and immediate observation need not report the new physical output.
Snapshots retain their values after later engine changes.

Other thruster types are not exposed by this addon. See the [Propulsion API reference]({{ '/guest-api/propulsion/' | relative_url }})
for exact signatures. Build commands, serialization behavior and test coverage live in
[Maintain first-party addons]({{ '/ADDON-CONTRIBUTING/' | relative_url }}#propulsion).
