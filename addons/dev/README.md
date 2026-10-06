# All-addon development stand

This independent Gradle build runs the main mod, all first-party addons and the pinned Aeronautics/Sable/Propulsion
runtime together. It produces no distributable umbrella mod.

The [canonical Wiki guide](https://certifiedbadideas.github.io/Compukters/DEV-STAND/) owns setup commands, runtime
versions, temporary chunk-loading controls and lifecycle verification details.

For an interactive client from the repository root:

```sh
cd addons/dev
./gradlew runClient
```

For checkout setup and verification policy, start with the
[contributor guides](https://certifiedbadideas.github.io/Compukters/CONTRIBUTORS/).
