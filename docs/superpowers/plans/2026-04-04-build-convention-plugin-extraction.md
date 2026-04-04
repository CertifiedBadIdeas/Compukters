# Build Convention Plugin Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move repeated Gradle build logic from version and loader leaf modules into layered convention plugins under `build-scripts` while preserving current module behavior.

**Architecture:** Keep `kotlin-convention` as the baseline, refactor version plugins to publish shared version context, add loader and packaging convention plugins, and shrink leaf module scripts to composition plus explicit common-module wiring. Keep module identity explicit instead of deriving it from project paths.

**Tech Stack:** Gradle Kotlin DSL, precompiled script plugins, Architectury Loom, Kotlin JVM

---

### Task 1: Add shared build logic helpers

**Files:**
- Create: `build-scripts/src/main/kotlin/compukter-kraft.build-logic.gradle.kts`
- Modify: `build-scripts/build.gradle.kts`
- Test: `build-scripts:build`

- [ ] **Step 1: Write the failing test**

The failure to target is plugin code duplication rather than a unit test. First, create the helper plugin file with references that do not exist yet in downstream scripts so the later build migration has a shared place to compile against.

```kotlin
val libs = the<VersionCatalogsExtension>().named("libs")

enum class LoaderKind {
    FABRIC,
    FORGE,
    NEOFORGE,
}

data class CkBuildContext(
    val versionKey: String,
    val minecraftVersion: String,
    val javaVersion: Int,
)
```

- [ ] **Step 2: Run build logic compile to verify it fails before the helper API is wired**

Run: `./gradlew :build-scripts:build`
Expected: FAIL because downstream convention plugins still inline their own logic and do not use the new shared helper/plugin definitions yet.

- [ ] **Step 3: Write minimal implementation**

Add a shared precompiled script plugin that centralizes reusable helper functions for:

```kotlin
fun Project.readModProperties(): MutableMap<String, String>
fun Project.computeModVersion(minecraftVersion: String): String
fun Project.configureGeneratedMetadata(modProperties: Map<String, String>)
fun Project.setBuildContext(versionKey: String, minecraftVersion: String, javaVersion: Int)
fun Project.buildContext(): CkBuildContext
```

Also expose loader-aware dependency helpers that will later move out of leaf modules.

- [ ] **Step 4: Run build logic compile to verify it passes**

Run: `./gradlew :build-scripts:build`
Expected: PASS for the helper plugin compilation stage.

- [ ] **Step 5: Commit**

```bash
git add build-scripts/build.gradle.kts build-scripts/src/main/kotlin/compukter-kraft.build-logic.gradle.kts
git commit -m "build: add shared convention helper logic"
```

### Task 2: Refactor version plugins to publish shared version context

**Files:**
- Modify: `build-scripts/src/main/kotlin/1.20.1-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/1.21.1-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/1.21.11-convention.gradle.kts`
- Test: `build-scripts:build`

- [ ] **Step 1: Write the failing test**

Capture the target shape by making the version plugins depend on shared helper APIs and removing leaf-module assumptions from their bodies.

```kotlin
plugins {
    id("compukter-kraft.build-logic")
    id("kotlin-convention")
    id("dev.architectury.loom")
    id("architectury-plugin")
}

setBuildContext(
    versionKey = "v1201",
    minecraftVersion = libs.findVersion("minecraft-v1201").get().toString(),
    javaVersion = 17,
)
```

- [ ] **Step 2: Run build logic compile to verify it fails if the helpers are incomplete**

Run: `./gradlew :build-scripts:build`
Expected: FAIL until all version plugins compile against the shared helper surface.

- [ ] **Step 3: Write minimal implementation**

For each version plugin, reduce the body to:

```kotlin
val context = buildContext()

kotlin {
    jvmToolchain(context.javaVersion)
}

java {
    sourceCompatibility = JavaVersion.toVersion(context.javaVersion)
    targetCompatibility = JavaVersion.toVersion(context.javaVersion)
}

architectury {
    minecraft = context.minecraftVersion
}
```

Keep version-specific mapping and dependency catalog lookups in the version plugin, but move repeated property publication and common dependency wiring through shared helpers where practical.

- [ ] **Step 4: Run build logic compile to verify it passes**

Run: `./gradlew :build-scripts:build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add build-scripts/src/main/kotlin/1.20.1-convention.gradle.kts build-scripts/src/main/kotlin/1.21.1-convention.gradle.kts build-scripts/src/main/kotlin/1.21.11-convention.gradle.kts
git commit -m "build: refactor version convention plugins"
```

### Task 3: Add loader and packaging convention plugins

**Files:**
- Create: `build-scripts/src/main/kotlin/fabric-convention.gradle.kts`
- Create: `build-scripts/src/main/kotlin/forge-convention.gradle.kts`
- Create: `build-scripts/src/main/kotlin/neoforge-convention.gradle.kts`
- Create: `build-scripts/src/main/kotlin/fabric-packaging-convention.gradle.kts`
- Create: `build-scripts/src/main/kotlin/forge-packaging-convention.gradle.kts`
- Create: `build-scripts/src/main/kotlin/neoforge-packaging-convention.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Test: `build-scripts:build`

- [ ] **Step 1: Write the failing test**

Define the plugin aliases and create the plugin files with references to shared context so build logic compilation fails until all plugins are implemented.

```toml
[plugins]
fabricConvention = { id = "fabric-convention" }
forgeConvention = { id = "forge-convention" }
neoforgeConvention = { id = "neoforge-convention" }
fabricPackagingConvention = { id = "fabric-packaging-convention" }
forgePackagingConvention = { id = "forge-packaging-convention" }
neoforgePackagingConvention = { id = "neoforge-packaging-convention" }
```

- [ ] **Step 2: Run build logic compile to verify it fails**

Run: `./gradlew :build-scripts:build`
Expected: FAIL until the new plugins compile and resolve all shared helper calls.

- [ ] **Step 3: Write minimal implementation**

Implement loader plugins with only loader-specific responsibilities.

```kotlin
architectury {
    platformSetupLoomIde()
    fabric()
}

dependencies {
    modImplementation(libs.findLibrary("fabric-loader-${buildContext().versionKey}").get())
    modImplementation(libs.findLibrary("fabric-api-${buildContext().versionKey}").get())
    modImplementation(libs.findLibrary("architectury-fabric-${buildContext().versionKey}").get())
}
```

Implement packaging plugins with shared metadata/resource expansion:

```kotlin
val modProperties = readModProperties()
modProperties["mod_version"] = computeModVersion(buildContext().minecraftVersion)

configureGeneratedMetadata(modProperties)

base.archivesName = modProperties["mod_name"]!!.replace(" ", "")
version = modProperties["mod_version"]!!
```

Keep loader-specific metadata ranges as a small extension or extra-property input consumed by packaging plugins.

- [ ] **Step 4: Run build logic compile to verify it passes**

Run: `./gradlew :build-scripts:build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build-scripts/src/main/kotlin/fabric-convention.gradle.kts build-scripts/src/main/kotlin/forge-convention.gradle.kts build-scripts/src/main/kotlin/neoforge-convention.gradle.kts build-scripts/src/main/kotlin/fabric-packaging-convention.gradle.kts build-scripts/src/main/kotlin/forge-packaging-convention.gradle.kts build-scripts/src/main/kotlin/neoforge-packaging-convention.gradle.kts
git commit -m "build: add loader and packaging conventions"
```

### Task 4: Migrate representative loader modules

**Files:**
- Modify: `modules/v1_20_1/v1_20_1-fabric/build.gradle.kts`
- Modify: `modules/v1_20_1/v1_20_1-forge/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Test: `:modules:v1_20_1:v1_20_1-fabric:tasks`
- Test: `:modules:v1_20_1:v1_20_1-forge:tasks`
- Test: `:modules:v1_21_1:v1_21_1-neoforge:tasks`

- [ ] **Step 1: Write the failing test**

Reduce each representative module to the target composition shape.

```kotlin
plugins {
    idea
    alias(libs.plugins.v1201)
    alias(libs.plugins.fabricConvention)
    alias(libs.plugins.fabricPackagingConvention)
}

dependencies {
    implementation(project(path = projects.v1201Common.path, configuration = "namedElements"))
}
```

- [ ] **Step 2: Run module task listing to verify it fails until all required context is supplied**

Run: `./gradlew :modules:v1_20_1:v1_20_1-fabric:tasks`
Expected: FAIL until the new plugin composition is complete.

- [ ] **Step 3: Write minimal implementation**

For each representative module:

- remove duplicated helper functions
- remove duplicated mod-properties parsing
- remove duplicated generated metadata tasks
- keep only explicit common-module project dependency
- provide only module-specific metadata range values when required

Example loader-range configuration for NeoForge:

```kotlin
extra["ck.minecraftVersionRange"] = "[1.21.1, 1.22)"
extra["ck.neoforgeVersionRange"] = "[21.1,)"
extra["ck.loaderVersionRange"] = "[4,)"
```

- [ ] **Step 4: Run the representative verification commands**

Run:
- `./gradlew :modules:v1_20_1:v1_20_1-fabric:tasks`
- `./gradlew :modules:v1_20_1:v1_20_1-forge:tasks`
- `./gradlew :modules:v1_21_1:v1_21_1-neoforge:tasks`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_20_1/v1_20_1-fabric/build.gradle.kts modules/v1_20_1/v1_20_1-forge/build.gradle.kts modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts
git commit -m "build: migrate representative loader modules"
```

### Task 5: Migrate remaining loader modules and verify the repository

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-fabric/build.gradle.kts`
- Modify: `modules/v1_21_11/v1_21_11-fabric/build.gradle.kts`
- Modify: `modules/v1_21_11/v1_21_11-neoforge/build.gradle.kts`
- Test: `check` or targeted build tasks

- [ ] **Step 1: Write the failing test**

Apply the same reduced composition shape to the remaining loader leaf modules.

```kotlin
plugins {
    idea
    alias(libs.plugins.v12111)
    alias(libs.plugins.neoforgeConvention)
    alias(libs.plugins.neoforgePackagingConvention)
}
```

- [ ] **Step 2: Run a targeted module command to verify failures are due to incomplete migration**

Run: `./gradlew :modules:v1_21_11:v1_21_11-neoforge:tasks`
Expected: FAIL until the remaining modules are migrated.

- [ ] **Step 3: Write minimal implementation**

Migrate the remaining modules with the same pattern used in Task 4 and keep only the explicit common-module dependency plus minimal range config.

- [ ] **Step 4: Run final verification**

Run:
- `./gradlew :build-scripts:build`
- `./gradlew :modules:v1_20_1:v1_20_1-fabric:tasks :modules:v1_20_1:v1_20_1-forge:tasks :modules:v1_21_1:v1_21_1-fabric:tasks :modules:v1_21_1:v1_21_1-neoforge:tasks :modules:v1_21_11:v1_21_11-fabric:tasks :modules:v1_21_11:v1_21_11-neoforge:tasks`
- `./gradlew check`

Expected: PASS, or if `check` is too heavy, document the exact failing unrelated task and keep the convention-plugin migration verified through the targeted commands.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-fabric/build.gradle.kts modules/v1_21_11/v1_21_11-fabric/build.gradle.kts modules/v1_21_11/v1_21_11-neoforge/build.gradle.kts
git commit -m "build: finish convention plugin extraction"
```