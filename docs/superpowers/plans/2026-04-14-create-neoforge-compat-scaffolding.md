# Create NeoForge Compat Scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a NeoForge-first internal Create compatibility module with optional dependency wiring, guarded bootstrap, and a stable place for future Create storage-ordering work.

**Architecture:** The implementation adds a dedicated `v1_21_1-create-neoforge` subproject and keeps every direct Create reference inside it. The existing `v1_21_1-neoforge` leaf remains the only always-loaded bootstrap module and activates compatibility only when Create is installed.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury Loom, NeoForge, Kotlin test.

---

## File Structure

- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build-scripts/src/main/kotlin/kotlin-convention.gradle.kts`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/build.gradle.kts`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatBootstrap.kt`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatRegistrar.kt`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatBootstrapTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Modify: `docs/ARCHITECTURE.md`

## Execution Consistency Note

At planning time, the official Modrinth API reports a NeoForge 1.21.1 Create release with version number `mc1.21.1-6.0.9`. This plan uses Modrinth Maven for the dependency declaration:

- repository: `https://api.modrinth.com/maven`
- coordinate: `maven.modrinth:create:mc1.21.1-6.0.9`

If that artifact disappears or the project later standardizes on a different official repository, update the catalog entry and repository URL together before executing Task 2 Step 3.

This plan only scaffolds the compatibility module and runtime wiring. The concrete feature requested later in brainstorming, ordering items from Create storage through the computer, requires a follow-up feature spec once the compat boundary exists.

### Task 1: Scaffold the Create compat module with a guarded bootstrap

**Files:**
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/build.gradle.kts`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatBootstrap.kt`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatRegistrar.kt`
- Create: `modules/v1_21_1/v1_21_1-create-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatBootstrapTest.kt`

- [ ] **Step 1: Write the failing bootstrap test**

```kotlin
package ru.lazyhat.compukterkraft.impl.create

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateCompatBootstrapTest {
    @Test
    fun initializeIfPresentOnlyRunsRegistrarWhenCreateIsInstalled() {
        var registrations = 0

        val absentResult = CreateCompatBootstrap.initializeIfPresent(
            isCreateLoaded = { false },
            register = { registrations++ },
        )
        val presentResult = CreateCompatBootstrap.initializeIfPresent(
            isCreateLoaded = { true },
            register = { registrations++ },
        )

        assertFalse(absentResult)
        assertTrue(presentResult)
        assertTrue(registrations == 1)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :v1_21_1-create-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.create.CreateCompatBootstrapTest"`
Expected: FAIL because the new module and bootstrap class do not exist yet.

- [ ] **Step 3: Add the new module build file and bootstrap implementation**

`modules/v1_21_1/v1_21_1-create-neoforge/build.gradle.kts`

```kotlin
@file:Suppress("PropertyName")

plugins {
    alias(libs.plugins.v1211)
}

val libs = the<VersionCatalogsExtension>().named("libs")

architectury {
    platformSetupLoomIde()
}

dependencies {
    add("neoForge", libs.findLibrary("neoforge-v1211").get())
    modImplementation(libs.findLibrary("architectury-neoforge-v1211").get())

    implementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    implementation(projects.core)

    testImplementation(kotlin("test"))

    modCompileOnly(libs.findLibrary("create-neoforge-v1211").get())
}
```

`modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatBootstrap.kt`

```kotlin
package ru.lazyhat.compukterkraft.impl.create

object CreateCompatBootstrap {
    fun initializeIfPresent(
        isCreateLoaded: () -> Boolean,
        register: () -> Unit = CreateCompatRegistrar::register,
    ): Boolean {
        if (!isCreateLoaded()) {
            return false
        }

        register()
        return true
    }
}
```

`modules/v1_21_1/v1_21_1-create-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/create/CreateCompatRegistrar.kt`

```kotlin
package ru.lazyhat.compukterkraft.impl.create

import ru.lazyhat.compukterkraft.core.LOGGER

object CreateCompatRegistrar {
    fun register() {
        LOGGER.info { "Create compatibility enabled" }
    }
}
```

- [ ] **Step 4: Run the test to verify the guarded bootstrap passes**

Run: `./gradlew :v1_21_1-create-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.create.CreateCompatBootstrapTest"`
Expected: PASS.

- [ ] **Step 5: Commit the isolated compat scaffold**

```bash
git add modules/v1_21_1/v1_21_1-create-neoforge
git commit -m "feat: scaffold neoforge create compat module"
```

### Task 2: Register the module in settings and dependency management

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build-scripts/src/main/kotlin/kotlin-convention.gradle.kts`

- [ ] **Step 1: Add a failing settings include for the new module**

`settings.gradle.kts`

```kotlin
val v1_21_1Dir = modulesDir.resolve("v1_21_1")
include("v1_21_1-common", v1_21_1Dir)
+include("v1_21_1-create-neoforge", v1_21_1Dir)
include("v1_21_1-neoforge", v1_21_1Dir)
include("v1_21_1-fabric", v1_21_1Dir)
```

- [ ] **Step 2: Run Gradle help to verify dependency resolution is still incomplete before catalog wiring**

Run: `./gradlew :v1_21_1-create-neoforge:help`
Expected: FAIL with a missing catalog alias or unresolved Create dependency.

- [ ] **Step 3: Add the verified Create alias and repository**

`gradle/libs.versions.toml`

```toml
[libraries]
create-neoforge-v1211 = "maven.modrinth:create:mc1.21.1-6.0.9"
```

`build-scripts/src/main/kotlin/kotlin-convention.gradle.kts`

```kotlin
repositories {
    mavenCentral {
        name = "Central"
    }
    maven("https://maven.neoforged.net/releases/") {
        name = "NeoForged"
    }
    maven("https://maven.parchmentmc.org/") {
        name = "Parchment MC"
    }
    maven("https://api.modrinth.com/maven") {
        name = "Create"
    }
}
```

- [ ] **Step 4: Re-run Gradle help to verify the module resolves**

Run: `./gradlew :v1_21_1-create-neoforge:help`
Expected: SUCCESS.

- [ ] **Step 5: Commit the build registration changes**

```bash
git add settings.gradle.kts gradle/libs.versions.toml build-scripts/src/main/kotlin/kotlin-convention.gradle.kts
git commit -m "build: register create compat module dependencies"
```

### Task 3: Wire the compat module into the NeoForge leaf and metadata

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/neoforge.mods.toml`

- [ ] **Step 1: Extend the leaf build so the new source set is packaged into the same mod**

`modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`

```kotlin
loom {
    mods {
        maybeCreate("main").apply {
            sourceSet("main", project(projects.v1211Common.path))
            sourceSet("main", project(projects.v1211CreateNeoforge.path))
            sourceSet("main", project(projects.core.path))
        }
    }
}

dependencies {
    implementation(project(path = projects.v1211Common.path, configuration = "namedElements"))
    implementation(project(path = projects.v1211CreateNeoforge.path, configuration = "namedElements"))
}
```

- [ ] **Step 2: Make the NeoForge bootstrap call the guarded compat initializer**

`modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt`

```kotlin
package ru.lazyhat.compukterkraft.impl

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.fml.ModList
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.network.server.ServerNetworking
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.MOD_NAME
import ru.lazyhat.compukterkraft.impl.create.CreateCompatBootstrap
import ru.lazyhat.compukterkraft.impl.platform.NetworkHandler

@Mod(MOD_ID)
class CompukterKraftMod(
    modEventBus: IEventBus,
    dist: Dist,
) {
    init {
        LOGGER.info { "$MOD_ID has started!" }

        ModRegistry.register(modEventBus)
        ModObjects.computerBlockEntityType = {
            @Suppress("UNCHECKED_CAST")
            ModRegistry.BlockEntities.COMPUTER_ADVANCED.get()
        }
        ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
        ModObjects.openComputerMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            player.openMenu(
                SimpleMenuProvider(
                    computer,
                    computer.name,
                ),
            ) { buffer ->
                menuData.toBytes(buffer)
            }
        }
        ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
        ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
        ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }
        NetworkHandler.setup(modEventBus)
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer

        CreateCompatBootstrap.initializeIfPresent(
            isCreateLoaded = { ModList.get().isLoaded("create") },
        )

        if (dist != Dist.CLIENT) {
            modEventBus.addListener(::onServerSetup)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info { "Initializing server... with $MOD_NAME!" }
    }
}
```

- [ ] **Step 3: Mark Create as optional in NeoForge metadata**

`modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/neoforge.mods.toml`

```toml
[[dependencies.${mod_id}]]
modId="create"
mandatory=false
versionRange="[0,)"
ordering="AFTER"
side="BOTH"
```

- [ ] **Step 4: Run focused compilation to verify the leaf wiring**

Run: `./gradlew :v1_21_1-neoforge:compileKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit the leaf integration wiring**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt modules/v1_21_1/v1_21_1-neoforge/src/main/resources/META-INF/neoforge.mods.toml
git commit -m "feat: wire neoforge create compat bootstrap"
```

### Task 4: Document the new compat boundary in architecture docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Add the Create compat module to the architecture overview**

`docs/ARCHITECTURE.md`

```md
| `v1_x_x-create-neoforge` | Optional NeoForge-only compat module for Create integration; all direct Create imports stay here |
```

- [ ] **Step 2: Add a short note under module ownership rules**

`docs/ARCHITECTURE.md`

```md
- optional third-party compat modules should live beside loader/version leaves and must not leak third-party API imports into `core` or `v1_x_x-common`
```

- [ ] **Step 3: Run the core architecture tests to ensure boundary checks still pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest"`
Expected: PASS.

- [ ] **Step 4: Commit the architecture documentation update**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: record create compat module boundary"
```

## Self-Review Notes

- Spec coverage: this plan implements the agreed NeoForge-first module boundary, optional dependency model, guarded activation, and documentation updates.
- Deliberate exclusion: the newly clarified feature of ordering items from Create storage is not implemented here because it needs its own behavior spec on top of this scaffold.
- External prerequisite resolved at planning time: Modrinth Maven currently serves `maven.modrinth:create:mc1.21.1-6.0.9` for NeoForge 1.21.1.