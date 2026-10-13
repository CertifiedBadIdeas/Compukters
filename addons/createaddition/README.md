# Compukters: Crafts & Additions

Independent NeoForge 1.21.1 integration for **Create: Crafts & Additions 1.7.2**. This build is tracked in the
Compukters workspace and depends on the public addon SDK and upstream mods, without importing other Compukters addons.
Its initial API version is **1.0** and its bounded base-mod compatibility comes from the staged SDK.

Applying a straw creates a liquid Blaze Burner. The configurator can bind and name that burner; Guest programs
select addon `createaddition` and use:

```kotlin
import createaddition.boiler.LiquidBurner

fun main() {
    val status = LiquidBurner.named("boiler_burner_1").status()
    println("Recoverable liquid: ${status.fuelAmount}/${status.fuelCapacity} mB")
    println("Already burning: ${status.burnTicks} ticks")
}
```

`LiquidBurner` uses the common typed peripheral provider API (`named`, `first`, `all`, optional/predicate forms).
`status()` is one atomic server-thread observation of amount/capacity, Create heat ordinal and remaining burning ticks.
Heat values are 0=NONE, 1=SMOULDERING, 2=FADING, 3=KINDLED and 4=SEETHING. Quantity is liquid in the tank, independently
of fuel already converted to burning time. The API is read-only and never fills, drains or extinguishes a burner.
A replaced, unloaded or disconnected burner invalidates its exact-instance handle; reacquire through the provider.
The base enforces exclusive program ownership. Active observations do not support execution hibernation.

Capability `createaddition:createaddition` version 1.0 introduces operation 0, returning the immutable four-I32
`BurnerStatus` record; `src/compuktersAddon/addon.lock` is authoritative. It adds an optional addon contract without
changing the platform bundle format, existing capability IDs or VM/native ABI. Host snapshots use the standard
NeoForge fluid capability and upstream public heat/burn-time accessors; no mirrored fuel state is persisted.

Build against this checkout after staging its SDK:

```sh
./gradlew-sandbox-dev-parallel-summary stageAddonDevelopmentDependencies # Compukters root
cd addons/createaddition
./gradlew-sandbox-dev-parallel-summary check runGameTestServer
```

Gradle runs on JDK 25; Minecraft and the addon target JDK 21. Workspace sandbox builds initialize their independent
cache with the shared reflink helper. `check` validates host behavior, generated ABI lock, production archive contents
and GameTest isolation. `runGameTestServer` exercises actual fuel capabilities and Guest execution through the public
compiler/terminal/peripheral boundaries. This is a local platform check, not a universal release claim.

The [boiler SCADA project](../../examples/boiler-scada/README.md) combines this addon with the separate Create addon and
uses a Redstone Port to control a kinetic battery and reversible pumps. The all-addon `addons/dev` build composes both
integrations with Crafts & Additions and Create: Connected for that stand.
