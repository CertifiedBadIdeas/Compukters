# Compukters: Propulsion

Independent Creative Thruster addon for Minecraft 1.21.1, NeoForge 21.1.252, Create 6.0.10, Sable 2.0.5 and
Propulsion: Simulated 1.1.5 (Modrinth H13U56dc). Install alongside Compukters and the upstream mods. It has no dependency
on Compukters: Create or Compukters: Sable; the main mod has no Propulsion dependency.

Pinned Propulsion also needs Simulated 1.3.1 at runtime, although its metadata omits that requirement.
The standalone and shared dev builds supply it from the Aeronautics 1.3.1 bundle.

Build the standalone archive with `./gradlew buildProductionJar`; `check` also verifies its Guest bundle,
mixin resources and separation from upstream, base-mod and GameTest implementations.

## Guest API

Select `addons = ["propulsion"]` in `compukter.toml` (format 3). Connect the computer and engine with peripheral cables
and name the engine with the Peripheral Configurator. A multiblock contact resolves to its controller.

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

Only the ordinary Creative Thruster is supported. Creative Vector Thruster and other thruster types are separate work.

## Development

Use Gradle on JDK 25 with a Java 21 toolchain:

```sh
./gradlew-sandbox-dev-parallel-summary check
./gradlew-sandbox-dev-parallel runGameTestServer
```

For the combined Create/Sable/Propulsion addon and Aeronautics runtime, use `../dev`.
The standalone build extracts the pinned upstream Sable libraries for Loom; these are not bundled into this addon.

## Verification

On 2026-10-04, standalone `check` and both required GameTests passed. The shared dev stand also passed all five
Create/Sable/Propulsion scenarios with the complete pinned Aeronautics runtime. The control test runs real Guest
programs, validates invalid inputs, exclusive ownership, NBT copies, cable loss, ordinary completion, explicit close,
tiny digital input clearing and computer removal. The assembly test forms a real 2×2×2 engine, copies it onto a Sable
construction, checks retained configuration and cleared control, and rejects the original handle.
Interactive flight under changing load remains a manual scenario; no additional VM or Propulsion ticks are introduced.
