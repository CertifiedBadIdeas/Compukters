# All-addon development stand

This independent Gradle build composes the main Compukters development archive and every independent addon.
It pins Minecraft 1.21.1, NeoForge 21.1.252, Create 6.0.10 and Sable 2.0.5. It has no distributable umbrella JAR.
Neither the main mod nor either addon depends on the other addon.

Run from this directory with Gradle on JDK 25 and a Java 21 toolchain available:

```sh
./gradlew-sandbox-dev-parallel-summary verifyAddons
./gradlew-sandbox-dev-parallel runGameTestServer
./gradlew-sandbox-dev-parallel runClient
./gradlew-sandbox-dev-parallel runServer
```

`verifyAddons` checks both standalone addons and verifies that their development archives contain only their own
implementation. The common GameTest server loads complete test archives instead of the ordinary addon archives,
registering the two Create scenarios and the Sable assembly/return scenario together. Client and ordinary server
runs load the ordinary archives. Each archive is built by its owning included Gradle build. Addon Guest bundles use the current workspace SDK tooling
and platform bundle, so launches do not mix a stale published compiler with the base mod from this checkout.

To add another independent addon, include its build in `settings.gradle.kts`, add its stable development and GameTest
archives to the maps in `build.gradle.kts`, and declare its upstream runtime dependencies here. The addon keeps its
own standalone checks, test registration and metadata. The shared stand owns only launch and verification composition.
