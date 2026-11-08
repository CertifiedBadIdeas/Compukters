# Guest Kotlin platform prototype for IntelliJ

This developer prototype connects the independent Compukters target to the real IntelliJ Kotlin K2 plugin.
It reads canonical `.cpb` metadata, enables multi-field value classes, uses Kotlin's own completion and type
checking, and supplies source navigation from that same bundle. Guest modules receive no JVM SDK or JVM
stdlib dependencies from this adapter.

The fixture's `prototype.flight.Velocity` is an ordinary Guest MFVC. Its executable `direction` and `advance`
methods use the real `kotlin.math.Vector3d`. A separate consumer imports the compiled addon library and checks
its results in the pinned Rust VM. No server operations, capability selectors or ABI lock entries are introduced
for these computations.

## Build and verify

Run from the Compukters checkout after `worktree-task doctor`:

```sh
./gradlew-sandbox-dev-parallel-summary testKotlinGuestLibraryPrototypeVmConformance
```

This produces the reusable addon bundle, its linked consumer and a platform carrying its metadata under
`modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/`:

- `prototype.cagb`: independently compiled addon metadata, source archive and executable library fragment.
- `consumer.cpkt`: linked consumer program, executed by the conformance task in the Rust VM.
- `prototype-platform.cpb`: the actual base platform plus the compiled addon module, for the IDE experiment.

Build and test the IDE plugin against an installed IntelliJ IDEA:

```sh
./gradlew-sandbox-dev-parallel-summary -p tools/intellij-guest-platform \
  formatKotlin test lintKotlin buildPlugin \
  -PideaHome=/absolute/path/to/intellij-idea \
  -PguestPlatform="$PWD/modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/prototype-platform.cpb"
```

Tests run inside that IDE's platform and bundled Kotlin plugin, using isolated configuration, plugins and
indexes under `.intellijPlatform/sandbox`. They do not use the standalone analysis worker. The packaged plugin
ZIP is written to `tools/intellij-guest-platform/build/distributions/`.

The tests cover a source-owned MFVC with executable methods, a consumer of the compiled addon without its
source in the project, actual member completion for stdlib and addon methods, an incorrect argument diagnostic,
and navigation into the bundle's actual stdlib source.

## Try in a separate IDE sandbox

Use `runIde` with the same Gradle properties to start the plugin's development sandbox. Create a dedicated
source module, and put `compukters-guest.properties` in its **module content root**:

```properties
platform=/absolute/path/to/prototype-platform.cpb
```

Copy `fixture/consumer/main.kt` into its source root. The `prototype.flight.Velocity` import is resolved from
the compiled library metadata. Completion after a `Velocity` or `Vector3d` receiver uses Guest declarations.
The marker opts the entire IDE module into the Guest platform. Keep the addon host module separate.

The Gradle sandbox provides the complete experiment without modifying an existing IDE installation.

## Compatibility and remaining work

Verified against IntelliJ IDEA 2026.1.5.1, build `261.27258.136`, with its bundled Kotlin
`2.4.0-ij261-71`, on Linux. The adapter is deliberately pinned to this IDE build. It compiles the shared
platform and symbol-provider sources against the IDE APIs, rather than packaging the compiler worker's
Kotlin 2.4.10 runtime. Its ZIP must not contain a second Kotlin compiler or Analysis API distribution.

IDEA 261's project-structure service requires its internal `KaSourceModuleBase` implementation, even when a
module is supplied through the K2 module-factory extension point. The small version-specific adapter subclasses
that base. This is an internal API dependency, with explicit Kotlin visibility suppressions; it needs an upstream
extension contract or per-version maintenance before a supported plugin can be promised. The IDE's MFVC feature
flag is also version-specific (`ValueClasses`, versus the compiler worker's `JvmInlineMultiFieldValueClasses`).

The marker and platform bundle are read once per module instance; changing them requires reopening the project.
The prototype is not a Gradle model importer, build/run integration, general dependency resolver, debugger or
complete IDE implementation. Automatic import of `src/contract/kotlin`, completion of unimported library names,
and complete library-source tooling remain separate follow-up work. Navigation currently opens a read-only
source view; overload-specific navigation and editable library-source projects are not implemented.

The production `contract` source set and declarative generator retain their existing SDK behavior. This slice
proves that executable Guest libraries and an actual IntelliJ platform can share native Guest types. It does not
yet merge ordinary executable classes and annotated server declarations into one new authoring pipeline.
Existing annotated server operations still reject bodies and keep their checked-in selector locks.
