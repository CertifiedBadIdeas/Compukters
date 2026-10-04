# Compukters: Sable development adapter

Independent experimental adapter for Minecraft 1.21.1, NeoForge 21.1.252 and Sable 2.0.5 (Modrinth version U678xqle). Main Compukters does not depend on this build or Sable. Create remains a separate addon.

This stage provides an internal on-demand host snapshot reader. It does **not** yet register a Guest API or expose a usable flight controller. Structured Guest responses are tracked in [#687](https://github.com/CertifiedBadIdeas/Compukters/issues/687).

## Observation ownership

Reading resolves the construction containing the computer at request time and copies its logical pose and solver linear/angular velocity on the server thread. No event handler, body registry, subscription, cached feed or background collection is installed. Without a request, this adapter performs no snapshot work.

Returned values are immutable copies containing construction UUID and world game tick. The world tick is not a physics substep or a promise of fresh simulation when physics is paused. Pose scale and rotation point are preserved for the later coordinate contract. Reading a snapshot never advances or schedules a VM; Guest delivery remains pending.

## Development commands

Run from this directory, with Gradle on JDK 25 and a Java 21 toolchain available:

```sh
./gradlew-sandbox-dev-parallel-summary compileGameTestKotlin lintKotlin
./gradlew-sandbox-dev-parallel runGameTestServer
./gradlew-sandbox-dev-parallel runClient
```

The lifecycle GameTest exercises an actual Sable assembly and a return move, computer identity/runtime replacement, on-demand snapshots and return to world-only behavior. This is not evidence for every full disassembly, split or merge scenario.

The pinned Sable JAR embeds Companion, Rapier and Veil; Veil embeds additional libraries. This build extracts those exact versions for the development classpath rather than independently selecting potentially mismatched dependencies. These extracted files are build outputs and are not copied into this addon archive.
