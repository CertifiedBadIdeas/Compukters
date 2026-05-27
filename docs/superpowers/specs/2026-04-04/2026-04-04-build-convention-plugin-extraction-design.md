# Build Convention Plugin Extraction Design

## Goal

Finish the ongoing Gradle refactor by moving repeated build logic from version and loader leaf modules into layered convention plugins under `build-scripts`.

## Current State

- `kotlin-convention` exists and already centralizes the Kotlin JVM baseline.
- Version-specific plugins `1.20.1-convention`, `1.21.1-convention`, and `1.21.11-convention` exist, but currently duplicate nearly the same logic.
- Loader leaf modules still duplicate:
  - `architectury` loader setup
  - loader-specific dependency helpers
  - `mod.properties` parsing and version/archive wiring
  - `generateModMetadata` and `processResources` wiring

## Target Architecture

The build should use layered convention plugins with clear responsibilities.

### 1. Base Kotlin Plugin

- `kotlin-convention`
- Responsibility: Kotlin JVM plugin, kotlinter, common repositories, JUnit platform setup.

### 2. Version Baseline Plugins

- `1.20.1-convention`
- `1.21.1-convention`
- `1.21.11-convention`

Responsibilities:

- apply `kotlin-convention`
- apply `dev.architectury.loom` and `architectury-plugin`
- configure Java and Kotlin toolchains for the target Minecraft line
- configure `architectury.minecraft`
- configure mappings for that version
- add common implementation and test dependencies shared by version modules

These plugins should differ only in version-specific facts such as Minecraft version, mappings, and Java level.

### 3. Loader Plugins

- `fabric-convention`
- `forge-convention`
- `neoforge-convention`

Responsibilities:

- configure loader-specific `architectury` setup (`fabric()`, `forge()`, `neoForge()`)
- add loader-specific repositories when required
- add loader-specific dependencies from the version catalog
- expose loader-specific helper behavior now duplicated in leaf modules:
  - `fabricImplementation`
  - `forgeImplementation`
  - `neoForgeImplementation`

### 4. Packaging Plugins

- `fabric-packaging-convention`
- `forge-packaging-convention`
- `neoforge-packaging-convention`

Responsibilities:

- parse `config/mod.properties`
- compute `mod_version` as `<minecraftVersion>-<rootProject.version>`
- configure `base.archivesName` and project `version`
- configure resource expansion via `generateModMetadata`
- wire generated resources into `processResources` and `sourceSets.main.resources`
- populate loader-specific placeholder values such as:
  - `minecraft_version_range`
  - `loader_version_range`
  - `neoforge_version_range`

## Configuration Flow

Layered plugins should communicate through explicit shared configuration, not by inferring module identity from the project path.

### Shared Version Context

Each version plugin should publish enough context for downstream plugins to reuse, including:

- version key such as `v1201`, `v1211`, `v12111`
- resolved Minecraft version string
- target Java version

This can be exposed through Gradle extra properties or a small extension object.

### Loader Context

Each loader plugin should publish its loader kind, for example:

- `fabric`
- `forge`
- `neoforge`

Packaging plugins can then consume that loader kind without inspecting the project path.

### Leaf Module Input

Leaf loader modules should keep only minimal, explicit module-specific configuration:

- which shared common module to depend on
- optional metadata range values that genuinely vary by module

The dependency on the version-specific common module remains explicit in each leaf build script and should not be guessed by plugins.

## Resulting Leaf Module Shape

### Common Modules

Common modules stay thin:

- apply the version plugin
- keep the `architectury { common(...) }` block

### Loader Modules

Loader modules should be reduced to:

- applying the version plugin
- applying the loader plugin
- applying the packaging plugin
- declaring the explicit dependency on the corresponding common module
- declaring only the minimal module-specific metadata range values when needed

## Migration Sequence

1. Add the new loader and packaging convention plugins in `build-scripts`.
2. Refactor the version plugins so they provide shared version context instead of repeating full leaf module setup assumptions.
3. Migrate one representative module per loader family to validate the plugin boundaries.
4. Migrate the remaining loader leaf modules.
5. Keep common modules thin and largely unchanged unless the new plugin boundaries require a small adjustment.

## Verification Plan

Use staged verification during migration.

### Build Logic Verification

- `./gradlew :build-scripts:build`

### Representative Module Verification

- `./gradlew :modules:v1_20_1:v1_20_1-fabric:tasks`
- `./gradlew :modules:v1_20_1:v1_20_1-forge:tasks`
- `./gradlew :modules:v1_21_1:v1_21_1-neoforge:tasks`

### Repository Verification

- run a broader verification task such as `./gradlew check` if feasible after representative modules succeed

## Success Criteria

- loader leaf module scripts are much shorter and primarily compose convention plugins
- repeated helper functions for loader dependency wiring are removed from leaf modules
- repeated metadata and generated-resources wiring is removed from leaf modules
- version differences are localized to version plugins and explicit module configuration
- the build remains readable and debuggable without path-based magic

## Non-Goals

- automatic discovery of the common module from the project path
- broad restructuring of the module graph unrelated to build logic extraction
- changing published artifact semantics beyond preserving the current behavior in centralized form