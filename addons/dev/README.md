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

For a controlled comparison of VM result delivery, launch from the repository root with
`./gradlew-sandbox-dev-parallel -p addons/dev runClient -PcompuktersVmOwnerWakeups=false`.
This disables queued owner wakeups and delivers results through the existing server pre-tick pump.
Omit the property or set it to `true` for ordinary delivery. Keep the same Guest program, controller settings,
initial pose and redstone signal in both runs. This switch only changes result delivery; frame credits,
instruction quotas, Guest timers and physics stepping remain enabled. It takes effect on the next JVM launch;
for an IDE-generated run configuration, use the JVM option `-Dcompukters.vm.ownerWakeups=false` directly.

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
