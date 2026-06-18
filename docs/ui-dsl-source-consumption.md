# UI DSL Source Consumption

The reusable UI DSL lives in the Gradle project `:ui-dsl`.

The first supported external consumption mode is source-based. It does not
require Maven Central, GitHub Packages, JitPack, or any other artifact host.

## Git Submodule

Add the future DSL repository as a submodule in the consuming project:

```bash
git submodule add <dsl-repository-url> vendor/kraft-ui-dsl
```

Then include it as a Gradle composite build:

```kotlin
pluginManagement {
    includeBuild("vendor/kraft-ui-dsl/build-scripts")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

includeBuild("vendor/kraft-ui-dsl")
```

The consumer then depends on the module using the coordinate exposed by the
included build:

```kotlin
dependencies {
    implementation("ru.lazyhat:kraft-ui-dsl")
}
```

Until the DSL is moved into a separate repository, Compukter Kraft consumes it
as a normal in-tree Gradle project dependency:

```kotlin
dependencies {
    implementation(projects.uiDsl)
}
```

`implementation("ru.lazyhat:kraft-ui-dsl")` is the intended standalone
included-build coordinate. In-tree Compukter Kraft usage remains
`projects.uiDsl` until the repository split happens.

## Boundary

The DSL module must not depend on Minecraft, NeoForge, Architectury, Compukter
runtime, Workbench, or mod integration classes. Host-specific renderers adapt
their own payloads to the generic DSL interfaces.
