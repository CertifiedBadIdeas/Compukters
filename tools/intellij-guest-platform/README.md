# Compukters Guest Kotlin for IntelliJ

The adapter imports native Guest Kotlin from addons using `ru.lazyhat.compukters.addon`. It creates a separate
IDE module for `src/contract/kotlin` and uses actual Guest platform and dependency metadata, including MFVCs,
stdlib methods and executable addon helpers. Host source modules keep their ordinary JVM model.

Install the ZIP through **Settings → Plugins → Install Plugin from Disk**, restart IDEA and reload the addon
Gradle project. The installed plugin is named **Compukters Guest Kotlin**. Enable the **Compukters Guest** status
widget through the status-bar context menu if hidden. It shows active, loading or error state; click it for
module names, input paths and the native compile task. Import does not compile the addon or change its ABI lock.
Imported Guest modules and their `contract`/`kotlin` roots have a theme-aware purple name and a `Guest` label
in the Project tree. Kotlin recognizes these modules as configured by their native Gradle model.
Run `assembleCompuktersAddon` to compile; use `updateCompuktersAddonAbiLock` explicitly for contract changes.

Platform and Guest dependency changes reload native analysis automatically. Imported configuration persists in
`.idea/compukters-guest.xml`; Gradle reimport updates it and removes obsolete modules. Invalid input files show an
error while the module remains native. Generated compiler inputs remain inspectable under
`build/generated/sources/compuktersGuest/kotlin`.

## Build and verify

Run from the Compukters task checkout after `worktree-task doctor`:

```sh
./gradlew-sandbox-dev-parallel-summary testKotlinGuestLibraryPrototypeVmConformance :addon-gradle-plugin:jar
```

The conformance task builds an independent `prototype.flight.Velocity` library, its linked consumer and a platform
with its metadata under `modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/`.
The consumer executes its MFVC methods using real `kotlin.math.Vector3d` in the pinned Rust VM.

Build against an installed IDEA and a local Gradle distribution:

```sh
./gradlew-sandbox-dev-parallel-summary -p tools/intellij-guest-platform formatKotlin \
  -PideaHome=/absolute/path/to/intellij-idea \
  -PguestPlatform="$PWD/modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/prototype-platform.cpb"

./gradlew-sandbox-dev-parallel-summary -p tools/intellij-guest-platform test lintKotlin buildPlugin \
  -PideaHome=/absolute/path/to/intellij-idea \
  -PguestPlatform="$PWD/modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/prototype-platform.cpb" \
  -PguestGradlePluginJar="$PWD/modules/common/addon-gradle-plugin/build/libs/addon-gradle-plugin-0.8.0.jar" \
  -PguestGradleHome=/absolute/path/to/gradle-9.8.1 \
  -PguestBasePlatform="$PWD/modules/common/guest-platform/build/platform/compukters-platform.cpb" \
  -PguestDependency="$PWD/modules/common/compiler-k2/build/generated/conformance/guest-library-prototype/prototype.cagb"
```

Tests run in the real installed IDE/Kotlin platform with isolated configuration and indexes. They cover MFVC
methods, compiled dependencies, generated command/provider completion, operation restrictions, source navigation,
actual Gradle import, artifact reload and removal on reimport. They do not modify the user's IDE installation.
The ZIP is written to `build/distributions/` under this adapter project.

Use `runIde` with the same properties for a separate development sandbox. A non-Gradle diagnostic fixture can
still opt its whole module in using `compukters-guest.properties` in the content root, with
`platform=/absolute/path/to/platform.cpb`. That manual fixture mode reads the marker once per module instance;
use Gradle import for normal addon authoring and automatic reload.

## Compatibility

Verified on Linux against IntelliJ IDEA 2026.1.5.1, build `261.27258.136`, with bundled Kotlin `2.4.0-ij261-71`.
The adapter is pinned to that build and compiles shared native platform/frontend sources against its APIs.
The ZIP does not bundle the worker compiler, a second Kotlin runtime or an Analysis API distribution.

IDEA's project-structure service requires an internal `KaSourceModuleBase` subclass. The adapter therefore needs
per-version maintenance or a future upstream extension contract. Its MFVC flag is also IDE-specific (`ValueClasses`).
Navigation opens read-only bundled sources; overload-specific navigation, editable library projects, a debugger,
and build/run controls inside the editor remain outside this adapter.
