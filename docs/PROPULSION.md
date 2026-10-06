---
layout: default
title: Propulsion addon
description: Control Creative Thrusters and Creative Vector Thrusters from Guest Kotlin.
section: players
permalink: /PROPULSION/
---

# Compukters: Propulsion

Independent Creative Thruster and Creative Vector Thruster addon for Minecraft 1.21.1, NeoForge 21.1.252, Create 6.0.10, Sable 2.0.5 and
Propulsion: Simulated 1.1.5 (Modrinth H13U56dc). Install alongside Compukters and the upstream mods. It has no dependency
on Compukters: Create or Compukters: Sable; the main mod has no Propulsion dependency.

Addon releases use `x.y` (API compatibility line and compatible update), separately from the Compukters target line.
For example, `compukters-propulsion-1.21.1-neoforge-0.5-1.0.jar` targets Compukters 0.5 and is addon version 1.0. Exact
minimum versions are enforced by loader metadata. See [addon versioning]({{ '/ADDON-DEVELOPMENT/' | relative_url }}#first-party-addon-versions).

Pinned Propulsion also needs Simulated 1.3.1 at runtime, although its metadata omits that requirement.
The standalone and shared dev builds supply it from the Aeronautics 1.3.1 bundle.

Build the standalone archive with `./gradlew buildProductionJar`; `check` also verifies its Guest bundle,
mixin resources and separation from upstream, base-mod and GameTest implementations.

## Guest API

Select `addons = ["propulsion"]` in `compukter.toml` (format 3). Place the engine against any face of the computer or
connect them with peripheral cables, and name the engine with the Peripheral Configurator. Ordinary Creative Thruster
multiblock contacts resolve to their controller.

For **Creative Vector Thruster** (`createpropulsion:creative_vector_thruster`):

```kotlin
import propulsion.thrusters.Thrusters

fun main() {
    val engine = Thrusters.creativeVector("main-engine")
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
import propulsion.thrusters.Thrusters

fun main() {
    val engine = Thrusters.creative("main-engine")
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
Each host admits at most 256 live handles; handle IDs are not reused within that host lifetime.

Upstream saves digital mode/input in NBT. A narrowly scoped mixin marks saves of engines controlled by Compukters and
clears the unowned digital input/mode on full NBT loading, including copies during assembly. Engine configuration
survives; live control is neither transferred to the copy nor serialized as an enduring program lease. Ordinary
uncontrolled Propulsion and ComputerCraft saves and client update packets retain their upstream behavior.

## State and units

`state()` returns an immutable `CreativeThrusterState` from one server-thread request. It includes world `gameTick`,
requested `throttle`, `effectiveThrottle`, `thrustPercent`, `configuredThrustKn`, `currentThrustKn`, `startupProgress`,
`active`, `startingUp`, `fadingOut`, multiblock `width`, and `unobstructedBlocks`.
Configured and current thrust are kilonewtons; current output converts Propulsion units using the server's conversion
factor. In the pinned 1.1.5 binary, `getTargetThrustNewtons()` actually returns configured kN; this adapter respects the
binary's computation rather than treating the misleading name as a unit guarantee. Current thrust is the last output
computed by Propulsion's ordinary tick, so a command and immediate observation need not report the new physical output.
Snapshots retain their values after later engine changes. The SDK bounds structured results; this API requires
Runtime ABI 1.13 / native C ABI 20, already provided by the current checkout.

Other thruster types are not exposed by this addon. Existing ordinary-engine operations keep ABI operation IDs 0..4;
vector operations use IDs 5..11 and `mount()` appends ID 12 in capability version 1.
Rebuild the addon bundle and Guest programs to use the new API.

## Development

Run from `addons/propulsion` with Gradle on JDK 25 and a Java 21 toolchain:

```sh
./gradlew-sandbox-dev-parallel-summary check
./gradlew-sandbox-dev-parallel runGameTestServer
```

For combined Create/Sable/Propulsion and Aeronautics runs, use the [all-addon development stand]({{ '/DEV-STAND/' | relative_url }}).
The standalone build extracts the pinned upstream Sable libraries for Loom; these are not bundled into this addon.

## Verification

Standalone `check` verifies the archive and both ordinary and vector GameTest sources. Real Guest programs exercise
exclusive ownership, invalid inputs, typed observations, cable loss, completion, explicit close, tiny input clearing,
computer removal, NBT copies and actual Sable assembly. Vector tests also preserve live redstone steering signals and
client packets. The shared dev stand composes these scenarios with Create and Sable tests and pinned Aeronautics.
Mount observations are checked in the ordinary world and on an assembled four-engine Sable platform with rotated
peripheral names, including neutral force directions and read-only ownership.
Interactive flight under changing load remains a manual scenario; no additional VM or Propulsion ticks are introduced.
