# Minecraft 26.1 and JDK 25 Baseline Migration Implementation Plan

> Issue: [#514](https://github.com/CertifiedBadIdeas/Compukters/issues/514)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the active Minecraft 1.21.1 integration with a buildable, tested Minecraft 26.1.2 / NeoForge 26.1.2.97 integration on repository-wide JDK 25.

**Architecture:** Keep the `common` plus `neoforge` module boundary and Architectury's source transformer, but use Architectury Loom's no-remap plugin because Minecraft 26.1 ships official production names. Remove the unused Architectury runtime API, retain the existing computer lifecycle semantics, and make the shadow archive the single inspected production artifact.

**Tech Stack:** Gradle 9.7.1, Kotlin 2.4.10, JDK 25, Architectury Loom 1.17.491, Architectury Plugin 3.5.169, Minecraft 26.1.2, NeoForge 26.1.2.97, JUnit/Kotlin Test, NeoForge GameTest, Rust/JNI full verification.

---

## File Map

- `settings.gradle.kts` and `build-scripts/settings.gradle.kts`: enforce the Gradle launcher JDK and select the only active Minecraft family.
- `gradle/libs.versions.toml`: pin the 26.1 toolchain and remove inactive 1.21, Parchment, Fabric, and Architectury runtime aliases.
- `build-scripts/src/main/kotlin/26.1-convention.gradle.kts`: configure Minecraft 26.1.2 through Loom no-remap with official names.
- `build-scripts/src/main/kotlin/kotlin-convention.gradle.kts`: own the repository-wide JVM 25 toolchain and bytecode target.
- `build-scripts/src/main/kotlin/neoforge-convention.gradle.kts`: configure NeoForge without Architectury API and produce the no-remap shadow archive.
- `build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts`: point the universal production task at `shadowJar` instead of `remapJar`.
- `build-scripts/src/main/kotlin/BuildLogicSupport.kt`: remove the now-redundant per-Minecraft Java version field.
- `build.gradle.kts`: expose a dedicated active-baseline policy check in addition to the repository verification profiles.
- `config/mod.properties`: expose only 26.1.2-compatible NeoForge/Minecraft metadata properties.
- `modules/v26_1/v26_1-common`: own the unchanged computer behavior and the Minecraft 26.1 value-I/O adapter.
- `modules/v26_1/v26_1-neoforge`: own NeoForge 26.1 registration, metadata, resources, GameTest, and production archive verification.
- `modules/compiler-k2/build.gradle.kts`: launch the isolated K2 worker with the JDK 25 toolchain.
- `build.gradle.kts`: update verification task paths and add an active-baseline audit.
- `README.md` and `AGENTS.md`: document the 26.1.2 / JDK 25 development baseline and commands.

### Task 1: Lock the JDK 25 and Minecraft 26.1 Build Baseline

**Files:**
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `build-scripts/settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Rename: `build-scripts/src/main/kotlin/1.21.1-convention.gradle.kts` to `build-scripts/src/main/kotlin/26.1-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/26.1-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/kotlin-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/BuildLogicSupport.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`

- [ ] **Step 1: Add a failing active-baseline policy task**

Register `verifyActiveMinecraftBaseline` in `build.gradle.kts`. It must inspect active build/configuration files, excluding `.git`, build outputs, the VM submodule, and `docs/superpowers/{specs,plans}`. It fails with every matching file when it sees `v1_21_1`, Minecraft 1.21.x, Parchment, or Java/JDK 17 or 21 configuration. Task 7 expands the same policy to documentation and no-remap/runtime dependency rules after those files have been migrated:

```kotlin
val verifyActiveMinecraftBaseline =
    tasks.register("verifyActiveMinecraftBaseline") {
        group = "verification"
        description = "Rejects stale Minecraft/JDK baseline configuration."
        val activeFiles =
            fileTree(rootDir) {
                include("*.gradle.kts", "*.properties", "gradle/*.toml", "build-scripts/**", "config/**", "modules/**")
                exclude("**/build/**", "**/.gradle/**", "docs/superpowers/specs/**", "docs/superpowers/plans/**")
            }
        inputs.files(activeFiles)
        doLast {
            val forbidden = Regex("v1_21_1|1\\.21\\.1\\b|1\\.21\\.11|Parchment|Java 17|Java 21|JDK 17|JDK 21")
            val matches = activeFiles.files.filter { file -> file.isFile && forbidden.containsMatchIn(file.readText()) }
            check(matches.isEmpty()) {
                "stale Minecraft/JDK baseline references: ${matches.sorted().joinToString { it.relativeTo(rootDir).path }}"
            }
        }
    }
```

- [ ] **Step 2: Run the policy task and confirm it rejects the old baseline**

Run: `./gradlew-sandbox-dev-parallel verifyActiveMinecraftBaseline`

Expected: FAIL and list the current 1.21/Parchment/Java 17/21 build and documentation files.

- [ ] **Step 3: Pin the catalog to one no-remap 26.1 family**

Replace the Minecraft/plugin portion of `gradle/libs.versions.toml` with:

```toml
architectury-loom-no-remap = { id = "dev.architectury.loom-no-remap", version = "1.17.491" }
architectury-plugin = { id = "architectury-plugin", version = "3.5.169" }
v261 = { id = "26.1-convention" }

[versions]
minecraft-v261 = "26.1.2"

[libraries]
minecraft-v261 = { module = "net.minecraft:minecraft", version.ref = "minecraft-v261" }
neoforge-v261 = "net.neoforged:neoforge:26.1.2.97"
```

Delete the `v1211`, `v12111`, Parchment, Create, Fabric, and Architectury runtime aliases. Add the no-remap plugin dependency to `build-scripts/build.gradle.kts` and remove the regular Loom plugin dependency.

- [ ] **Step 4: Enforce JDK 25 before project configuration**

Add this guard after the license header in both settings scripts:

```kotlin
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_25)) {
    "Compukters requires Gradle to run on JDK 25 or newer; current JVM is ${System.getProperty(\"java.version\")}. " +
        "Set JAVA_HOME to a JDK 25 installation and retry."
}
```

Keep automatic toolchain downloads disabled.

- [ ] **Step 5: Make the shared Kotlin convention own JVM 25**

In `kotlin-convention.gradle.kts`, remove the Parchment repository, use `jvmToolchain(25)`, configure `compilerOptions.jvmTarget` to `JvmTarget.JVM_25`, and configure Java source/target compatibility plus toolchain to 25:

```kotlin
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
```

Change the K2 `forkedWorkerTest` launcher from `JavaLanguageVersion.of(17)` to `of(25)`.

- [ ] **Step 6: Replace the version convention with the no-remap 26.1 convention**

Rename the convention and use:

```kotlin
plugins {
    id("kotlin-convention")
    id("dev.architectury.loom-no-remap")
    id("architectury-plugin")
}

val libVersion = "v261"
val libs = the<VersionCatalogsExtension>().named("libs")
val minecraftVersion = libs.findVersion("minecraft-$libVersion").get().toString()
val minecraftLibrary = libs.findLibrary("minecraft-$libVersion").get()

setBuildContext(versionKey = libVersion, minecraftVersion = minecraftVersion)
version = computeModVersion()

architectury.minecraft = minecraftVersion

dependencies {
    minecraft(minecraftLibrary)
    testImplementation(kotlin("test"))
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
```

Remove `javaVersion` from `BuildContext` and `setBuildContext`; Java policy is no longer version-family state.

- [ ] **Step 7: Run the focused included-build tests**

Run: `./gradlew-sandbox-dev-parallel -p build-scripts test`

Expected: PASS under JDK 25. The root build intentionally remains between baselines until Task 2 renames its projects and paths.

- [ ] **Step 8: Continue directly into Task 2 without committing an unconfigurable root**

Do not commit yet: the catalog and convention changes require the module-family replacement in Task 2. Task 2 completes the same RED-to-GREEN cycle and commits the coherent baseline once.

### Task 2: Replace the Active Module Family and Metadata Inputs

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `config/mod.properties`
- Rename: `modules/v1_21_1` to `modules/v26_1`
- Rename: `modules/v26_1/v1_21_1-common` to `modules/v26_1/v26_1-common`
- Rename: `modules/v26_1/v1_21_1-neoforge` to `modules/v26_1/v26_1-neoforge`
- Modify: `modules/v26_1/v26_1-common/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`

- [ ] **Step 1: Point settings and verification at `v26_1`**

Use:

```kotlin
val v26_1Dir = modulesDir.resolve("v26_1")
include("v26_1-common", v26_1Dir)
include("v26_1-neoforge", v26_1Dir)
```

Change root verification dependencies to `:v26_1-common:test` and `:v26_1-neoforge:test`.

- [ ] **Step 2: Rename the modules and update project accessors**

Apply `libs.plugins.v261` in both modules. In the NeoForge module replace every old common accessor with Gradle's generated `projects.v261Common` accessor and retain `transformProductionNeoForge` for the Architectury common transformation.

- [ ] **Step 3: Replace metadata inputs with exact 26.1.2 compatibility**

Keep only:

```properties
# 26.1 family (pinned baseline: 26.1.2)
26.1.2_minecraft_version=26.1.2
26.1.2_minecraft_version_range=[26.1.2]
26.1.2_neoforge_mod_version_range=[26.1.2.97,)
```

Remove the obsolete JavaFML loader range because the 26.1 metadata format no longer declares `modLoader`/`loaderVersion`.

- [ ] **Step 4: Confirm only the new projects configure and the policy turns green**

Run: `./gradlew-sandbox-dev-parallel projects verifyActiveMinecraftBaseline`

Expected: PASS; the project list contains `v26_1-common` and `v26_1-neoforge`, contains no `v1_21_1` project, and the build/configuration policy reports no stale baseline.

- [ ] **Step 5: Commit the family replacement**

```bash
git add settings.gradle.kts build.gradle.kts build-scripts config/mod.properties gradle/libs.versions.toml modules
git commit -m "build(minecraft): establish the 26.1 and JDK 25 baseline (#514)"
```

### Task 3: Port Persistent Computer State to Minecraft 26.1 Value I/O

**Files:**
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/InstalledProgramStorage.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntity.kt`
- Modify: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/InstalledProgramStorageTest.kt`
- Modify: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntityTest.kt`

- [ ] **Step 1: Rewrite persistence tests around `ValueInput` and `ValueOutput`**

Create test helpers backed by `TagValueOutput`/`TagValueInput` and retain these assertions:

```kotlin
private fun InstalledProgramStorage.saveTag(): CompoundTag {
    val output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING)
    save(output)
    return output.buildResult()
}

private fun InstalledProgramStorage.loadTag(tag: CompoundTag) {
    load(TagValueInput.create(ProblemReporter.DISCARDING, EMPTY_PROVIDER, tag))
}
```

The round-trip must still produce one `compukters` child with schema `1` and the exact artifact bytes; malformed, empty, oversized, and unsupported-schema payloads must clear storage.

- [ ] **Step 2: Run the common tests and confirm the old NBT signatures fail**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test`

Expected: FAIL because 26.1 block entities override `loadAdditional(ValueInput)` and `saveAdditional(ValueOutput)`, not the old `CompoundTag, HolderLookup.Provider` methods.

- [ ] **Step 3: Store artifact bytes through a codec inside a namespaced child**

Change storage to accept value I/O and preserve the versioned shape:

```kotlin
fun save(root: ValueOutput) {
    val artifact = installedArtifact ?: return
    val payload = root.child(ROOT_KEY)
    payload.putInt(SCHEMA_KEY, CURRENT_SCHEMA)
    payload.store(ARTIFACT_KEY, Codec.BYTE_BUFFER, ByteBuffer.wrap(artifact.copyOf()))
}

fun load(root: ValueInput) {
    installedArtifact = null
    val payload = root.child(ROOT_KEY).orElse(null) ?: return
    if (payload.getIntOr(SCHEMA_KEY, 0) != CURRENT_SCHEMA) return
    val buffer = payload.read(ARTIFACT_KEY, Codec.BYTE_BUFFER).orElse(null) ?: return
    val artifact = ByteArray(buffer.remaining()).also(buffer.slice()::get)
    if (artifact.isEmpty() || artifact.size > maximumArtifactBytes) return
    installedArtifact = artifact
}
```

`ValueInput.child` and `ValueInput.read` both return `Optional`; absence at either level clears the prior artifact and returns without allocating a carrier.

- [ ] **Step 4: Port the block entity overrides**

Use:

```kotlin
override fun loadAdditional(input: ValueInput) {
    super.loadAdditional(input)
    closeCarrier()
    storage.load(input)
    transcript.clear()
    runtimeState = neverStarted()
}

override fun saveAdditional(output: ValueOutput) {
    super.saveAdditional(output)
    storage.save(output)
}
```

Update the test subclass to serialize through `TagValueOutput` and deserialize through `TagValueInput`.

- [ ] **Step 5: Run focused common behavior tests**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test`

Expected: PASS for defensive copying, bounds, lazy VM creation, one tick delegation, transient transcript, persistence, and idempotent close.

- [ ] **Step 6: Commit the value-I/O port**

```bash
git add modules/v26_1/v26_1-common
git commit -m "feat(minecraft): port computer storage to 26.1 value IO (#514)"
```

### Task 4: Port NeoForge Registration and Mod Metadata

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/registry/CompuktersRegistry.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Delete: `modules/v26_1/v26_1-neoforge/src/main/resources/pack.mcmeta`
- Delete: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/models/item/compukter.json`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/items/compukter.json`

- [ ] **Step 1: Add registration/resource assertions to the real GameTest and archive checks**

Keep the existing GameTest assertions for registered block/type and add production archive expectations for `assets/compukters/items/compukter.json`, absence of `models/item/compukter.json`, and absence of `pack.mcmeta`.

- [ ] **Step 2: Port registration to ID-aware 26.1 helpers**

Register the block and block item through the specialized helpers so registry IDs are installed into their properties:

```kotlin
val COMPUTER: DeferredBlock<ComputerBlock> =
    blocks.registerBlock(
        "compukter",
        { properties ->
            ComputerBlock(
                properties,
                ::NeoForgeComputerBlockEntity,
                Supplier { COMPUTER_BLOCK_ENTITY.get() },
            )
        },
        { properties -> properties.strength(2.0f) },
    )

val COMPUTER_ITEM: DeferredItem<BlockItem> = items.registerSimpleBlockItem(COMPUTER)

val COMPUTER_BLOCK_ENTITY =
    blockEntities.register("compukter") {
        BlockEntityType(::NeoForgeComputerBlockEntity, false, COMPUTER.get())
    }
```

- [ ] **Step 3: Migrate NeoForge metadata syntax**

Remove `modLoader` and `loaderVersion`. Change each dependency entry from `mandatory=true` to `type="required"`; keep exact expanded Minecraft and NeoForge ranges and no Architectury dependency.

- [ ] **Step 4: Migrate the item resource and remove obsolete pack metadata**

Create `assets/compukters/items/compukter.json`:

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "compukters:block/compukter"
  }
}
```

Keep blockstate, block model, localization, and loot table unchanged. Remove the legacy `pack.mcmeta`; the official 26.1 NeoForge MDK does not require a standalone pack descriptor for mod resources.

- [ ] **Step 5: Compile NeoForge main and process resources**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:compileKotlin :v26_1-neoforge:processResources`

Expected: PASS; generated metadata contains Minecraft `[26.1.2]`, NeoForge `[26.1.2.97,)`, and no Architectury dependency.

- [ ] **Step 6: Commit the loader port**

```bash
git add modules/v26_1/v26_1-neoforge/src/main modules/v26_1/v26_1-neoforge/src/test
git commit -m "feat(neoforge): port registration and resources to 26.1 (#514)"
```

### Task 5: Make the No-Remap Shadow JAR the Production Artifact

**Files:**
- Modify: `build-scripts/src/main/kotlin/neoforge-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`

- [ ] **Step 1: Remove Architectury API and remap-specific configuration**

Delete `modImplementation(versionLibrary("architectury-neoforge"))` and all `RemapJarTask` configuration. Keep `neoForge(versionLibrary("neoforge"))`, common transformation, core/native shadow inputs, and Kotlin runtime `include` dependencies.

- [ ] **Step 2: Configure `shadowJar` as the final official-namespace archive**

Use one classifier-free Shadow task:

```kotlin
val productionJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.named("assemble") {
    dependsOn(productionJar)
}
```

Use Loom 1.17's public task-bound nesting API to attach the `include` configuration to `shadowJar`:

```kotlin
loom {
    nestJars(productionJar, configurations.named("include"))
}
```

Do not reintroduce `remapJar`.

- [ ] **Step 3: Point the universal build task at the production provider**

In `loom-runs-convention.gradle.kts` make `buildProductionUniversalJar` depend on `shadowJar` and describe it as the unobfuscated production mod JAR.

- [ ] **Step 4: Generalize archive verification**

In the NeoForge module, type `productionJar` as `TaskProvider<ShadowJar>`, use `archiveFile` as the verifier input, and assert:

```kotlin
check(nativeEntries == listOf(nativeResourcePath))
check(entries.count { it == "META-INF/neoforge.mods.toml" } == 1)
check(entries.none { it.startsWith("dev/architectury/") })
check(entries.none { it.contains("kotlin/compiler") })
check(entries.any { it == "assets/compukters/items/compukter.json" })
```

Also assert the archive contains common/NeoForge computer classes and no gameTest classes.

- [ ] **Step 5: Build and inspect the production JAR**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar :v26_1-neoforge:verifyPackagedCompukterJni`

Expected: PASS with exactly one current-host JNI resource, no Architectury API, no K2 compiler implementation, one NeoForge metadata file, and all Compukters resources/classes.

- [ ] **Step 6: Commit the archive pipeline**

```bash
git add build-scripts/src/main/kotlin/neoforge-convention.gradle.kts build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts modules/v26_1/v26_1-neoforge/build.gradle.kts
git commit -m "build(neoforge): produce unobfuscated 26.1 mod jar (#514)"
```

### Task 6: Port the Computer GameTest to the 26.1 Registry Model

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`

- [ ] **Step 1: Replace removed annotation assumptions with a registered test instance**

Remove `@GameTestHolder`, `@PrefixGameTestTemplate`, and the old `@GameTest` method. Make the game-test object an `@EventBusSubscriber(modid = MOD_ID)` with an `@SubscribeEvent` handler for `RegisterGameTestsEvent`. Register an empty test environment and a custom `GameTestInstance` whose `TestData` is:

```kotlin
TestData(
    environment,
    Identifier.withDefaultNamespace("bastion/mobs/empty"),
    200,
    0,
    true,
    Rotation.NONE,
    false,
    1,
    1,
    false,
    0,
)
```

Its `run(GameTestHelper)` method must preserve the existing test: place the registered block, verify its block entity type, install `terminal-session.hex`, wait for auto-boot, replace it with air, then assert removal and `ProgramComputerState.Closed`. Implement `codec()` as `MapCodec.unit(this)` only to satisfy the runtime-only instance's abstract codec contract; the test is registered programmatically and is not decoded from a production datapack. Return `Component.literal("Compukters computer lifecycle")` from `typeDescription()`.

- [ ] **Step 2: Configure Loom's real GameTest server run**

Keep the `gameTest` source set bound to the main mod, but use Loom 1.17's no-remap run model:

```kotlin
runs.register("gameTestServer") {
    server()
    runDir("run/gameTestServer")
    property("neoforge.enabledGameTestNamespaces", MOD_ID)
    property("neoforge.gameTestServer", "true")
    property("compukter.vm.terminalFixture", terminalFixture.absolutePath)
    ideConfigGenerated(true)
}
```

Bind verification to Loom's generated `runGameTestServer` task.

- [ ] **Step 3: Compile the isolated GameTest source set**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:gameTestClasses`

Expected: PASS with the new `RegisterGameTestsEvent`, `TestData`, `Identifier`, and `GameTestInstance` APIs.

- [ ] **Step 4: Run the real GameTest server**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer`

Expected: server exits successfully after the computer registration/lifecycle test passes; missing registration, native loading, boot, ticking, removal, or close produces a distinct failed assertion.

- [ ] **Step 5: Recheck that GameTest code is absent from production**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar :v26_1-neoforge:verifyPackagedCompukterJni`

Expected: PASS and no `ComputerBlockGameTest` entry in the production archive.

- [ ] **Step 6: Commit the GameTest port**

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "test(neoforge): port computer GameTest to 26.1 (#514)"
```

### Task 7: Add Active-Baseline and Dependency Audits

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Complete the repository stale-baseline policy**

Extend `verifyActiveMinecraftBaseline` to inspect README, AGENTS, active documentation, and active source paths as well as build/configuration contents. Keep Git history and `docs/superpowers/{specs,plans}` excluded, require `modules/v26_1/v26_1-common` plus `modules/v26_1/v26_1-neoforge` to exist, and reject runtime `architectury-neoforge` plus `RemapJarTask` after the production pipeline has been ported.

- [ ] **Step 2: Verify resolved runtime dependency leakage**

Register `verifyNeoForgeRuntimeDependencies` in the NeoForge module. Resolve `runtimeClasspath`, collect module coordinates, and fail if any module group starts with `dev.architectury` or any module name contains `kotlin-compiler`. This checks the actual graph instead of source spelling.

- [ ] **Step 3: Bind audits and GameTest to verification profiles**

Make `verifyLocalFast` depend on `verifyActiveMinecraftBaseline`. Make `verifyLocalFull` depend on `:v26_1-neoforge:runGameTestServer` and `:v26_1-neoforge:verifyPackagedCompukterJni` in addition to existing Rust/JNI/compiler/playground integration.

- [ ] **Step 4: Run the focused audit**

Run:

```text
./gradlew-sandbox-dev-parallel verifyActiveMinecraftBaseline :v26_1-neoforge:verifyNeoForgeRuntimeDependencies
./gradlew-sandbox-dev-parallel -p build-scripts test
```

Expected: PASS with no active old-baseline or Architectury runtime references.

- [ ] **Step 5: Commit the guards**

```bash
git add build.gradle.kts modules/v26_1/v26_1-neoforge/build.gradle.kts
git commit -m "test(minecraft): guard the active 26.1 baseline (#514)"
```

### Task 8: Update Developer Documentation and Run Cumulative Verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/ARCHITECTURE.md` only if its active module/JDK statements require replacement

- [ ] **Step 1: Update public and agent-facing baseline documentation**

Document Minecraft 26.1.2, NeoForge 26.1.2.97, JDK 25, module paths `v26_1-*`, the no-remap production archive, and commands:

```text
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runClient
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer
./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar
```

Do not commit a machine-specific JDK path; state that `JAVA_HOME` or a Gradle-discoverable installation must select JDK 25.

- [ ] **Step 2: Run formatting and fast verification**

Run: `./gradlew-sandbox-dev-parallel formatKotlin verifyLocalFast --rerun-tasks`

Expected: PASS under a JDK 25 Gradle launcher.

- [ ] **Step 3: Run full repository verification**

Run: `./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks`

Expected: PASS for build scripts, all JVM modules, real NeoForge GameTest, production JAR inspection, Rust VM/JNI debug and release suites, Clippy/fmt, native integration, K2 conformance, and playground end-to-end execution.

- [ ] **Step 4: Perform final repository audits**

Run:

```bash
git diff --check
git status --short
git submodule status
git log -1 --format='%an <%ae>'
```

Expected: no whitespace errors; only intentional plan/progress changes before the final commit; initialized clean VM submodule; author `lazyhat`.

- [ ] **Step 5: Commit documentation and final migration state**

```bash
git add README.md AGENTS.md docs build.gradle.kts build-scripts config gradle modules
git commit -m "docs(minecraft): document the 26.1 development baseline (#514)"
```

- [ ] **Step 6: Update issue #514 only after verification evidence exists**

Comment with the exact passing commands, production JAR name, and commit hashes. Move #514 from Now to Done and close it only when every acceptance criterion in the design is satisfied. Do not push without explicit user authorization.
