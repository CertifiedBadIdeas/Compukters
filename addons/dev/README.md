# All-addon development stand

This independent Gradle build composes the main Compukters development archive and every independent addon.
It pins Minecraft 1.21.1 and NeoForge 21.1.252 with the following runtime:

| Mod | Version |
| --- | --- |
| Compukters + Compukters: Create + Compukters: Sable | Current checkout |
| Create | 6.0.10 |
| Sable | 2.0.5 |
| Aeronautics bundle (Aeronautics, Simulated, Offroad) | 1.3.1 |
| Create Propulsion: Simulated | 1.1.5 |

Pinned Modrinth versions: [Aeronautics Vzp221Un](https://modrinth.com/mod/create-aeronautics/version/Vzp221Un),
[Propulsion H13U56dc](https://modrinth.com/mod/create-propulsion-simulated/version/H13U56dc).
The exact bundled Aeronautics mods and Sable libraries are extracted for the Loom dev classpath; no separate versions
of Simulated/Offroad are selected. This stand has no distributable umbrella JAR.
Neither the main mod nor either addon depends on the other addon.

For an interactive client from the repository root:

```sh
cd addons/dev
./gradlew runClient
```

In IntelliJ, reload the linked `addons/dev` Gradle build and select its `runClient` task (or generated client run
configuration). The game directory is `addons/dev/run/client`; extra manually installed mods belong in its `mods`
subdirectory. The pinned mods above are already on the classpath and do not need copying there. Server and GameTest
worlds use separate `run/server` and `run/gameTestServer` directories. Gradle rebuilds the main mod and both addon
archives before launching, so editing them does not require a Maven Local publication.

Run these verification/development commands from this directory with Gradle on JDK 25 and a Java 21 toolchain available:

```sh
./gradlew-sandbox-dev-parallel-summary verifyAddons
./gradlew-sandbox-dev-parallel runGameTestServer
./gradlew-sandbox-dev-parallel runClient
./gradlew-sandbox-dev-parallel runServer
```

`verifyAddons` checks both standalone addons, verifies that their development archives contain only their own
implementation, and checks the complete pinned physics mod set through `verifyPhysicsMods`. Every client/server/GameTest
launch also performs that physics-set check. The common GameTest server loads complete test archives instead of the
ordinary addon archives,
registering the two Create scenarios and the Sable assembly/return scenario together. Client and ordinary server
runs load the ordinary archives. Each archive is built by its owning included Gradle build. Addon Guest bundles use
the current workspace SDK tooling
and platform bundle, so launches do not mix a stale published compiler with the base mod from this checkout.

To add another independent addon, include its build in `settings.gradle.kts`, add its stable development and GameTest
archives to the maps in `build.gradle.kts`, and declare its upstream runtime dependencies here. The addon keeps its
own standalone checks, test registration and metadata. The shared stand owns only launch and verification composition.

Compukters currently exposes the Create Guest API and request-only `sable.physics.Physics.snapshot()` on constructions.
Loading Aeronautics and Propulsion supplies their blocks and physics behavior; it does not add a separate Guest
Propulsion control API. For the snapshot example and project addon selection, see [the Sable README](../sable/README.md).


Verified with this pinned set: `verifyAddons`, all three required real GameTests, and client resource loading followed
by normal shutdown. Interactive construction/flight behavior still needs manual testing. Propulsion 1.1.5 emits
missing-model warnings (including oxidizer states and lodestone tracker overlay) from its upstream resources; these
do not prevent startup.
