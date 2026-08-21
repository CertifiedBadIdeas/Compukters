# UI DSL Source Consumption

The reusable UI DSL lives in the Git submodule `vendor/ui-dsl`. Compukters
still exposes it to the main Gradle build as the project `:ui-dsl`.

The first supported external consumption mode is source-based. It does not
require Maven Central, GitHub Packages, JitPack, or any other artifact host.

## Git Submodule

Clone Compukters with submodules or initialize the DSL submodule after
checkout:

```bash
git submodule update --init vendor/ui-dsl
```

External consumers can add the DSL repository as a submodule in their own
project:

```bash
git submodule add https://github.com/CertifiedBadIdeas/ui-dsl vendor/ui-dsl
```

Then include it as a Gradle composite build:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

includeBuild("vendor/ui-dsl")
```

The consumer then depends on the module using the coordinate exposed by the
included build:

```kotlin
dependencies {
    implementation("ru.lazyhat:kraft-ui-dsl")
}
```

Compukters consumes the submodule as a normal Gradle project dependency:

```kotlin
dependencies {
    implementation(projects.uiDsl)
}
```

`implementation("ru.lazyhat:kraft-ui-dsl")` is the standalone included-build
coordinate. In-tree Compukters usage remains `projects.uiDsl` while
`settings.gradle.kts` maps `:ui-dsl` to `vendor/ui-dsl`.

## Consumer Fixture

The repository includes a standalone consumer fixture that exercises the same
coordinate through a Gradle composite build:

```bash
./gradlew-sandbox-dev -p fixtures/ui-dsl-consumer test
```

This fixture intentionally lives outside the main production module graph. It
depends on `implementation("ru.lazyhat:kraft-ui-dsl")` and includes
`vendor/ui-dsl` as a composite build, matching the intended external
source-consumption path.

## Boundary

The DSL module must not depend on Minecraft, NeoForge, Architectury, Compukter
runtime, Workbench, or mod integration classes. Host-specific renderers adapt
their own payloads to the generic DSL interfaces.
