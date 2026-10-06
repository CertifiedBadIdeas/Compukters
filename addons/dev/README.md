# All-addon development stand

This independent Gradle build composes the main Compukters development archive and every independent addon.
It pins Minecraft 1.21.1 and NeoForge 21.1.252 with the following runtime:

| Mod | Version |
| --- | --- |
| Compukters + Compukters: Create + Compukters: Sable + Compukters: Propulsion | Current checkout |
| Create | 6.0.10 |
| Sable | 2.0.5 |
| Aeronautics bundle (Aeronautics, Simulated, Offroad) | 1.3.1 |
| Create Propulsion: Simulated | 1.1.5 |

Pinned Modrinth versions: [Aeronautics Vzp221Un](https://modrinth.com/mod/create-aeronautics/version/Vzp221Un),
[Propulsion H13U56dc](https://modrinth.com/mod/create-propulsion-simulated/version/H13U56dc).
The exact bundled Aeronautics mods and Sable libraries are extracted for the Loom dev classpath; no separate versions
of Simulated/Offroad are selected. This stand has no distributable umbrella JAR.
The main mod has no upstream physics dependency; the three Compukters addons remain independent.

For an interactive client from the repository root:

```sh
cd addons/dev
./gradlew runClient
```

In IntelliJ, reload the linked `addons/dev` Gradle build and select its `runClient` task (or generated client run
configuration). The game directory is `addons/dev/run/client`; extra manually installed mods belong in its `mods`
subdirectory. The pinned mods above are already on the classpath and do not need copying there. Server and GameTest
worlds use separate `run/server` and `run/gameTestServer` directories. Gradle rebuilds the main mod and all addon
archives before launching, so editing them does not require a Maven Local publication.

Temporary flight diagnostics currently keep a 3x3 area of chunks ticking around each loaded computer.
For Sable constructions the area follows the transformed world position of the computer, rather than its virtual
storage coordinates. Tickets refresh every ten ticks, expire after forty ticks, and are released when the computer
disappears, the server stops, or `/compuktersdev forcechunks off` is run. `/compuktersdev forcechunks on` enables them
again; these commands require operator permission. No vanilla force-load flags are saved to the world. This aid
belongs only to the development stand and starts disabled in GameTest runs.

Run these verification/development commands from this directory with Gradle on JDK 25 and a Java 21 toolchain available:

```sh
./gradlew-sandbox-dev-parallel-summary verifyAddons
./gradlew-sandbox-dev-parallel runGameTestServer
./gradlew-sandbox-dev-parallel runClient
./gradlew-sandbox-dev-parallel runServer
```

`verifyAddons` checks all standalone addons, verifies that their development archives contain only their own
implementation, and checks the complete pinned physics mod set through `verifyPhysicsMods`. Every client/server/GameTest
launch also performs that physics-set check. The common GameTest server loads complete test archives instead of the
ordinary addon archives, registering the two Create scenarios, Sable assembly/return and four Propulsion
control/assembly scenarios together. Client and ordinary server runs load the ordinary archives. Each archive is built
by its owning included Gradle build. Addon Guest bundles use the current workspace SDK tooling and platform bundle,
so launches do not mix a stale published compiler with the base mod from this checkout.

To add another independent addon, include its build in `settings.gradle.kts`, add its stable development and GameTest
archives to the maps in `build.gradle.kts`, and declare its upstream runtime dependencies here. The addon keeps its
own standalone checks, test registration and metadata. The shared stand owns only launch and verification composition.

Compukters exposes the Create Guest API, request-only `sable.physics.Physics.snapshot()` and the independent
`propulsion.thrusters.Thrusters.creative(name)` and `creativeVector(name)` control APIs. Select the required addon IDs
in each Guest project's
`compukter.toml`. See the [Sable README](../sable/README.md) and [Propulsion README](../propulsion/README.md) for examples,
units and control lifetime.

Verification on 2026-10-04: `verifyAddons` and the common GameTest server passed all seven required scenarios
(two Create, Sable assembly/return, ordinary and vector Propulsion ownership/lifetime and Sable assembly).
Interactive construction/flight behavior still needs manual testing. Propulsion 1.1.5 emits
missing-model warnings (including oxidizer states and lodestone tracker overlay) from its upstream resources; these
do not prevent startup.

The stand also registers three hibernation lifecycle characterization tests (issue
[#697](https://github.com/CertifiedBadIdeas/Compukters/issues/697)). They run with the actual pinned Create/Sable/physics
mods and the development chunk loader disabled:

- An ordinary computer's remote chunk unloads while two computers 64 blocks apart on a force-loaded Sable construction
  retain their runtime epochs and execute shell commands. Sable's internal plot chunks are distinct from the external
  world chunks; construction force-load tickets can keep external chunks available without vanilla force-load flags.
- Controlled construction serialization/reload exposes a valid physics body before either computer has an actor.
  Real physics substeps are counted before actor attachment and shell readiness. The test then removes the construction
  ticket and observes natural Sable unload/reload through its holding-chunk manager. A fixed joint holds the test body
  in place during asynchronous startup; the physics system is not globally paused. This joint is a fixture restraint,
  not a proposed restoration barrier.
- A Create bearing captures computer NBT but has no computer movement actor. A separate railway fixture advances five
  blocks through the real global railway manager with no loaded carriage entity and with Sable installed. It constructs
  a straight graph and serialized carriage directly; it does not prove station assembly, schedules, portals or trains
  running on tracks inside a moving Sable plot.

These tests characterize current behavior. They do not implement execution snapshots or prove that programs survive
unload/restart. Computers currently cold-boot on construction reload, and a per-construction physics readiness barrier
remains required for transparent restoration. `check` compiles the stand's GameTests; `runGameTestServer` executes them.
