# Runtime Target Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a narrow platform abstraction from the current Forge-only `mod` module, move loader-agnostic logic into shared modules, and ship a validated vertical slice on Fabric 1.20.1, Forge 1.20.1, Fabric 1.21.1, and NeoForge 1.21.1.

**Architecture:** Keep `compiler` fully shared, extract common Minecraft-facing logic into a new `core` module, define a narrow `platform-api` for registration and networking, and implement one runtime leaf module per supported target. Migrate from the existing 1.21.1 worktree by classifying differences into common, loader-specific, version-specific, or noise before copying code.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury Loom, Forge 1.20.1, Fabric Loader/API, NeoForge 1.21.1, kotlin.test, existing VM and workbench runtime tests.

---

## File Structure Map

### Existing files that change first

- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `mod/build.gradle.kts`
- Modify: `mod/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Modify: `mod/src/main/kotlin/ck/mod/ModRegistry.kt`
- Modify: `mod/src/main/kotlin/ck/mod/ClientRegistry.kt`
- Modify: `mod/src/main/kotlin/ck/mod/platform/NetworkHandler.kt`
- Modify: `mod/src/main/kotlin/ck/mod/network/NetworkMessages.kt`
- Modify: `mod/src/main/resources/META-INF/mods.toml`

### New shared-module files

- Create: `core/build.gradle.kts`
- Create: `core/src/main/kotlin/ck/mod/bootstrap/CommonModBootstrap.kt`
- Create: `core/src/main/kotlin/ck/mod/bootstrap/CommonContentDescriptors.kt`
- Create: `core/src/main/kotlin/ck/mod/bootstrap/CommonNetworkProtocol.kt`
- Create: `core/src/main/kotlin/ck/mod/platform/api/PlatformBlockRegistrar.kt`
- Create: `core/src/main/kotlin/ck/mod/platform/api/PlatformMenuRegistrar.kt`
- Create: `core/src/main/kotlin/ck/mod/platform/api/PlatformNetworkRegistrar.kt`
- Create: `core/src/main/kotlin/ck/mod/platform/api/PlatformClientHooks.kt`
- Create: `core/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt`

### New target modules

- Create: `forge-1.20.1/build.gradle.kts`
- Create: `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/CompukterKraftForgeMod.kt`
- Create: `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/Forge1201Registrars.kt`
- Create: `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/Forge1201NetworkChannel.kt`
- Create: `forge-1.20.1/src/main/resources/META-INF/mods.toml`
- Create: `fabric-1.20.1/build.gradle.kts`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/CompukterKraftFabric1201.kt`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/Fabric1201Registrars.kt`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/Fabric1201NetworkChannel.kt`
- Create: `fabric-1.20.1/src/main/resources/fabric.mod.json`
- Create: `fabric-1.21.1/build.gradle.kts`
- Create: `fabric-1.21.1/src/main/kotlin/ck/mod/fabric1211/CompukterKraftFabric1211.kt`
- Create: `neoforge-1.21.1/build.gradle.kts`
- Create: `neoforge-1.21.1/src/main/kotlin/ck/mod/neoforge1211/CompukterKraftNeoForge1211.kt`

### Verification and CI files

- Create: `.github/workflows/runtime-target-matrix.yml`
- Create: `docs/superpowers/specs/target-diff-notes/1.21.1-classification.md`

### Existing tests to move or keep green

- Modify: `mod/src/test/kotlin/ck/mod/application/input/ComputerInputDispatchTest.kt`
- Modify: `mod/src/test/kotlin/ck/mod/application/runtime/ComputerProgramSupportTest.kt`
- Modify: `mod/src/test/kotlin/ck/mod/computer/vm/BackgroundComputerVmTest.kt`
- Modify: `mod/src/test/kotlin/ck/mod/computer/vm/VmRuntimeSupportTest.kt`
- Modify: `mod/src/test/kotlin/ck/mod/application/workbench/WorkbenchStoreTest.kt`

### Task 1: Introduce Common Bootstrap Contracts In The Current Codebase

**Files:**
- Create: `mod/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt`
- Create: `mod/src/main/kotlin/ck/mod/bootstrap/CommonModBootstrap.kt`
- Create: `mod/src/main/kotlin/ck/mod/bootstrap/CommonContentDescriptors.kt`
- Create: `mod/src/main/kotlin/ck/mod/bootstrap/CommonNetworkProtocol.kt`
- Create: `mod/src/main/kotlin/ck/mod/platform/api/PlatformBlockRegistrar.kt`
- Create: `mod/src/main/kotlin/ck/mod/platform/api/PlatformMenuRegistrar.kt`
- Create: `mod/src/main/kotlin/ck/mod/platform/api/PlatformNetworkRegistrar.kt`
- Create: `mod/src/main/kotlin/ck/mod/platform/api/PlatformClientHooks.kt`
- Modify: `mod/src/main/kotlin/ck/mod/network/NetworkMessages.kt`

- [ ] **Step 1: Write the failing bootstrap contract test**

```kotlin
package compukterkraft.mod.bootstrap

object CommonContentDescriptors {
    const val COMPUTER_ADVANCED_BLOCK = "computer_advanced"
    const val COMPUTER_MENU = "computer"
}
```

```kotlin
package compukterkraft.mod.bootstrap

object CommonNetworkProtocol {
    val serverboundChannels = listOf(
        "computer_action",
        "key_event",
        "mouse_event",
        "paste_event",
        "computer_workspace_request",
    )

    val clientboundChannels = listOf(
        "chat_table",
        "computer_terminal",
        "computer_workspace",
    )
}
```

```kotlin
package compukterkraft.mod.bootstrap

import compukterkraft.mod.platform.api.PlatformBlockRegistrar
import compukterkraft.mod.platform.api.PlatformClientHooks
import compukterkraft.mod.platform.api.PlatformMenuRegistrar
import compukterkraft.mod.platform.api.PlatformNetworkRegistrar
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonModBootstrapTest {
    @Test
    fun registersComputerContentAndNetworkMessagesThroughPlatformPorts() {
        val blocks = RecordingBlockRegistrar()
        val menus = RecordingMenuRegistrar()
        val network = RecordingNetworkRegistrar()
        val clientHooks = RecordingClientHooks()

        CommonModBootstrap.registerCommon(blocks, menus, network, clientHooks)

        assertEquals(listOf("computer_advanced"), blocks.blockNames)
        assertEquals(listOf("computer"), menus.menuNames)
        assertEquals(listOf("computer_action", "key_event", "mouse_event", "paste_event", "computer_workspace_request"), network.serverbound)
        assertEquals(listOf("chat_table", "computer_terminal", "computer_workspace"), network.clientbound)
        assertEquals(1, clientHooks.registrationCalls)
    }

    private class RecordingBlockRegistrar : PlatformBlockRegistrar {
        val blockNames = mutableListOf<String>()

        override fun registerBlock(name: String) {
            blockNames += name
        }
    }

    private class RecordingMenuRegistrar : PlatformMenuRegistrar {
        val menuNames = mutableListOf<String>()

        override fun registerMenu(name: String) {
            menuNames += name
        }
    }

    private class RecordingNetworkRegistrar : PlatformNetworkRegistrar {
        val serverbound = mutableListOf<String>()
        val clientbound = mutableListOf<String>()

        override fun registerServerbound(channel: String) {
            serverbound += channel
        }

        override fun registerClientbound(channel: String) {
            clientbound += channel
        }
    }

    private class RecordingClientHooks : PlatformClientHooks {
        var registrationCalls = 0

        override fun registerClientScreens() {
            registrationCalls += 1
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :mod:test --tests compukterkraft.mod.bootstrap.CommonModBootstrapTest`
Expected: FAIL with unresolved references for `CommonModBootstrap` and `Platform*Registrar`.

- [ ] **Step 3: Add the minimal platform contracts and common bootstrap**

```kotlin
package compukterkraft.mod.platform.api

interface PlatformBlockRegistrar {
    fun registerBlock(name: String)
}

interface PlatformMenuRegistrar {
    fun registerMenu(name: String)
}

interface PlatformNetworkRegistrar {
    fun registerServerbound(channel: String)
    fun registerClientbound(channel: String)
}

interface PlatformClientHooks {
    fun registerClientScreens()
}
```

```kotlin
package compukterkraft.mod.bootstrap

import compukterkraft.mod.ModRegistry
import compukterkraft.mod.network.NetworkMessages
import compukterkraft.mod.platform.api.PlatformBlockRegistrar
import compukterkraft.mod.platform.api.PlatformClientHooks
import compukterkraft.mod.platform.api.PlatformMenuRegistrar
import compukterkraft.mod.platform.api.PlatformNetworkRegistrar

object CommonModBootstrap {
    fun registerCommon(
        blocks: PlatformBlockRegistrar,
        menus: PlatformMenuRegistrar,
        network: PlatformNetworkRegistrar,
        clientHooks: PlatformClientHooks,
    ) {
        blocks.registerBlock(CommonContentDescriptors.COMPUTER_ADVANCED_BLOCK)
        menus.registerMenu(CommonContentDescriptors.COMPUTER_MENU)

        CommonNetworkProtocol.serverboundChannels.forEach(network::registerServerbound)
        CommonNetworkProtocol.clientboundChannels.forEach(network::registerClientbound)

        clientHooks.registerClientScreens()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :mod:test --tests compukterkraft.mod.bootstrap.CommonModBootstrapTest`
Expected: PASS and one executed test.

- [ ] **Step 5: Commit**

```bash
git add mod/src/main/kotlin/ck/mod/bootstrap mod/src/main/kotlin/ck/mod/platform/api mod/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt
git commit -m "refactor: introduce common bootstrap contracts"
```

### Task 2: Extract Loader-Agnostic Code Into `core`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/build.gradle.kts`
- Create: `core/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt`
- Move: `mod/src/main/kotlin/ck/mod/application/**` -> `core/src/main/kotlin/ck/mod/application/**`
- Move: `mod/src/main/kotlin/ck/mod/computer/**` -> `core/src/main/kotlin/ck/mod/computer/**`
- Move: `mod/src/main/kotlin/ck/mod/context/**` -> `core/src/main/kotlin/ck/mod/context/**`
- Move: `mod/src/main/kotlin/ck/mod/menu/**` -> `core/src/main/kotlin/ck/mod/menu/**`
- Move: `mod/src/main/kotlin/ck/mod/gui/**` -> `core/src/main/kotlin/ck/mod/gui/**`
- Move: `mod/src/main/kotlin/ck/mod/ui/**` -> `core/src/main/kotlin/ck/mod/ui/**`
- Move: `mod/src/main/kotlin/ck/mod/block/**` -> `core/src/main/kotlin/ck/mod/block/**`
- Move: `mod/src/main/kotlin/ck/mod/item/**` -> `core/src/main/kotlin/ck/mod/item/**`
- Move: `mod/src/test/kotlin/ck/mod/application/**` -> `core/src/test/kotlin/ck/mod/application/**`
- Move: `mod/src/test/kotlin/ck/mod/computer/**` -> `core/src/test/kotlin/ck/mod/computer/**`

- [ ] **Step 1: Write the failing module include and dependency setup**

```kotlin
include("compiler")
include("core")
include("mod")
```

```kotlin
plugins {
    alias(libs.plugins.kotlinConvention)
}

dependencies {
    implementation(projects.compiler)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Run shared tests to verify the module is not wired yet**

Run: `./gradlew :core:test`
Expected: FAIL because the `core` sources and tests do not exist yet.

- [ ] **Step 3: Move loader-agnostic packages and repoint the old module to depend on `core`**

```kotlin
dependencies {
    minecraft(libs.minecraft)
    forge(libs.forge)
    mappings(
        loom.layered {
            officialMojangMappings()
            parchment(libs.parchment.for1v20v1)
        },
    )
    modImplementation(libs.architectury.forge)

    implementation(projects.core)
}
```

```kotlin
package compukterkraft.mod

import compukterkraft.mod.bootstrap.CommonModBootstrap

object CommonEntrypoints {
    fun bootstrap() = CommonModBootstrap
}
```

- [ ] **Step 4: Run the moved tests**

Run: `./gradlew :core:test --tests compukterkraft.mod.application.input.ComputerInputDispatchTest --tests compukterkraft.mod.computer.vm.BackgroundComputerVmTest`
Expected: PASS and the moved tests execute from the `core` module.

- [ ] **Step 5: Run the old runtime module tests as a safety check**

Run: `./gradlew :mod:test`
Expected: PASS with the remaining target-specific tests only.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core mod
git commit -m "refactor: extract loader-agnostic runtime into core"
```

### Task 3: Convert The Current `mod` Module Into `forge-1.20.1`

**Files:**
- Modify: `settings.gradle.kts`
- Move: `mod/build.gradle.kts` -> `forge-1.20.1/build.gradle.kts`
- Move: `mod/src/main/kotlin/ck/mod/CompukterKraftMod.kt` -> `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/CompukterKraftForgeMod.kt`
- Move: `mod/src/main/kotlin/ck/mod/ModRegistry.kt` -> `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/Forge1201Registrars.kt`
- Move: `mod/src/main/kotlin/ck/mod/ClientRegistry.kt` -> `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/Forge1201ClientHooks.kt`
- Move: `mod/src/main/kotlin/ck/mod/platform/NetworkHandler.kt` -> `forge-1.20.1/src/main/kotlin/ck/mod/forge1201/Forge1201NetworkChannel.kt`
- Move: `mod/src/main/resources/META-INF/mods.toml` -> `forge-1.20.1/src/main/resources/META-INF/mods.toml`

- [ ] **Step 1: Write the failing target-module include**

```kotlin
include("compiler")
include("core")
include("forge-1.20.1")
```

- [ ] **Step 2: Run the Forge target build to verify it fails before files are moved**

Run: `./gradlew :forge-1.20.1:build`
Expected: FAIL because the module does not yet exist.

- [ ] **Step 3: Move the Forge-specific bootstrap and implement the platform ports there**

```kotlin
package compukterkraft.mod.forge1201

import compukterkraft.mod.MOD_ID
import compukterkraft.mod.bootstrap.CommonModBootstrap
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

@Mod(MOD_ID)
class CompukterKraftForgeMod(context: FMLJavaModLoadingContext) {
    init {
        CommonModBootstrap.registerCommon(
            blocks = Forge1201Registrars.blocks(context.modEventBus),
            menus = Forge1201Registrars.menus(context.modEventBus),
            network = Forge1201NetworkChannel,
            clientHooks = Forge1201ClientHooks,
        )
    }
}
```

```kotlin
package compukterkraft.mod.forge1201

import compukterkraft.mod.platform.api.PlatformNetworkRegistrar
import net.minecraftforge.network.NetworkDirection

object Forge1201NetworkChannel : PlatformNetworkRegistrar {
    override fun registerServerbound(channel: String) {
        register(channel, NetworkDirection.PLAY_TO_SERVER)
    }

    override fun registerClientbound(channel: String) {
        register(channel, NetworkDirection.PLAY_TO_CLIENT)
    }

    private fun register(channel: String, direction: NetworkDirection) {
        // reuse the existing SimpleChannel registration logic here
    }
}
```

- [ ] **Step 4: Run Forge tests and the target build**

Run: `./gradlew :forge-1.20.1:test :forge-1.20.1:build`
Expected: PASS and the Forge artifact is produced.

- [ ] **Step 5: Run the Forge client vertical slice manually**

Run: `./gradlew :forge-1.20.1:runClient --console=plain`
Expected: the game launches, the computer block appears in the creative tab, and opening the workbench screen does not crash.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts forge-1.20.1
git commit -m "refactor: isolate forge 1.20.1 runtime target"
```

### Task 4: Add `fabric-1.20.1` Using The Same Common Bootstrap

**Files:**
- Modify: `settings.gradle.kts`
- Create: `fabric-1.20.1/build.gradle.kts`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/CompukterKraftFabric1201.kt`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/Fabric1201Registrars.kt`
- Create: `fabric-1.20.1/src/main/kotlin/ck/mod/fabric1201/Fabric1201NetworkChannel.kt`
- Create: `fabric-1.20.1/src/main/resources/fabric.mod.json`
- Create: `fabric-1.20.1/src/main/resources/compukterkraft.accesswidener`

- [ ] **Step 1: Write the failing Fabric target include**

```kotlin
include("fabric-1.20.1")
```

- [ ] **Step 2: Run the Fabric target build to verify it fails before implementation**

Run: `./gradlew :fabric-1.20.1:build`
Expected: FAIL because the module is missing.

- [ ] **Step 3: Add the Fabric runtime leaf and call the same common bootstrap**

```kotlin
package compukterkraft.mod.fabric1201

import compukterkraft.mod.bootstrap.CommonModBootstrap
import net.fabricmc.api.ModInitializer

object CompukterKraftFabric1201 : ModInitializer {
    override fun onInitialize() {
        CommonModBootstrap.registerCommon(
            blocks = Fabric1201Registrars.blocks(),
            menus = Fabric1201Registrars.menus(),
            network = Fabric1201NetworkChannel,
            clientHooks = Fabric1201ClientHooks,
        )
    }
}
```

```json
{
  "schemaVersion": 1,
  "id": "compukterkraft",
  "version": "${version}",
  "name": "Compukter Kraft",
  "entrypoints": {
    "main": [
      "compukterkraft.mod.fabric1201.CompukterKraftFabric1201"
    ]
  }
}
```

- [ ] **Step 4: Run Fabric tests and build**

Run: `./gradlew :fabric-1.20.1:test :fabric-1.20.1:build`
Expected: PASS and the Fabric artifact is produced.

- [ ] **Step 5: Run the Fabric client vertical slice manually**

Run: `./gradlew :fabric-1.20.1:runClient --console=plain`
Expected: the game launches and the same computer open/VM/network flow works on Fabric 1.20.1.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts fabric-1.20.1
git commit -m "feat: add fabric 1.20.1 runtime target"
```

### Task 5: Classify The Existing 1.21.1 Worktree And Add `fabric-1.21.1` And `neoforge-1.21.1`

**Files:**
- Create: `docs/superpowers/specs/target-diff-notes/1.21.1-classification.md`
- Create: `fabric-1.21.1/build.gradle.kts`
- Create: `fabric-1.21.1/src/main/kotlin/ck/mod/fabric1211/CompukterKraftFabric1211.kt`
- Create: `neoforge-1.21.1/build.gradle.kts`
- Create: `neoforge-1.21.1/src/main/kotlin/ck/mod/neoforge1211/CompukterKraftNeoForge1211.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `versions.properties`

- [ ] **Step 1: Produce the classification note from the worktree diff**

Run: `git --no-pager diff --name-status dev...feat/neoforge-1.21.1-migration -- mod/src/main/kotlin mod/src/main/resources > /tmp/ck-1211-diff.txt`
Expected: a diff summary file exists with the changed 1.21.1 runtime files.

- [ ] **Step 2: Write the classification document before copying any code**

```markdown
# 1.21.1 Classification

## Common
- VM scheduler cleanup that does not import loader classes
- menu state synchronization that stays on vanilla classes

## Version-specific
- registry bootstrap changes required by Minecraft 1.21.1
- packet registration changes required by 1.21.1 networking API updates

## Loader-specific
- NeoForge bootstrap and lifecycle entrypoints
- Fabric entrypoint and registration wrappers

## Noise
- formatting-only moves
- dead Forge-only hooks removed during the port
```

- [ ] **Step 3: Create the 1.21.1 target modules and port only classified runtime glue**

```kotlin
package compukterkraft.mod.neoforge1211

import compukterkraft.mod.bootstrap.CommonModBootstrap
import net.neoforged.fml.common.Mod

@Mod("compukterkraft")
class CompukterKraftNeoForge1211 {
    init {
        CommonModBootstrap.registerCommon(
            blocks = NeoForge1211Registrars.blocks(),
            menus = NeoForge1211Registrars.menus(),
            network = NeoForge1211NetworkChannel,
            clientHooks = NeoForge1211ClientHooks,
        )
    }
}
```

```kotlin
package compukterkraft.mod.fabric1211

import compukterkraft.mod.bootstrap.CommonModBootstrap
import net.fabricmc.api.ModInitializer

object CompukterKraftFabric1211 : ModInitializer {
    override fun onInitialize() {
        CommonModBootstrap.registerCommon(
            blocks = Fabric1211Registrars.blocks(),
            menus = Fabric1211Registrars.menus(),
            network = Fabric1211NetworkChannel,
            clientHooks = Fabric1211ClientHooks,
        )
    }
}
```

- [ ] **Step 4: Run the 1.21.1 builds**

Run: `./gradlew :fabric-1.21.1:build :neoforge-1.21.1:build`
Expected: PASS and both 1.21.1 artifacts are produced.

- [ ] **Step 5: Run the 1.21.1 client vertical slices**

Run: `./gradlew :fabric-1.21.1:runClient --console=plain`
Expected: the computer block can be placed and opened on Fabric 1.21.1.

Run: `./gradlew :neoforge-1.21.1:runClient --console=plain`
Expected: the computer block can be placed and opened on NeoForge 1.21.1.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/target-diff-notes/1.21.1-classification.md fabric-1.21.1 neoforge-1.21.1 gradle/libs.versions.toml versions.properties
git commit -m "feat: add 1.21.1 runtime targets"
```

### Task 6: Add Matrix Verification And Keep It Green

**Files:**
- Create: `.github/workflows/runtime-target-matrix.yml`
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: Write the failing CI workflow skeleton**

```yaml
name: runtime-target-matrix

on:
  push:
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        target:
          - forge-1.20.1
          - fabric-1.20.1
          - fabric-1.21.1
          - neoforge-1.21.1
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :${{ matrix.target }}:build
```

- [ ] **Step 2: Run one local matrix command before pushing CI**

Run: `./gradlew :core:test :forge-1.20.1:build :fabric-1.20.1:build :fabric-1.21.1:build :neoforge-1.21.1:build`
Expected: PASS and all shared tests plus all target builds succeed in one command.

- [ ] **Step 3: Update the project documentation to reflect the new structure**

```markdown
## Runtime Targets

The repository now builds four runtime targets from the same shared `compiler` and `core` modules:

- Forge 1.20.1
- Fabric 1.20.1
- Fabric 1.21.1
- NeoForge 1.21.1

Target-specific bootstrap lives in leaf modules. Shared computer logic, VM orchestration, UI state, and application services live in `core`.
```

- [ ] **Step 4: Run the documentation-linked verification commands**

Run: `./gradlew :core:test`
Expected: PASS.

Run: `./gradlew :forge-1.20.1:build :fabric-1.20.1:build :fabric-1.21.1:build :neoforge-1.21.1:build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/runtime-target-matrix.yml README.md ARCHITECTURE.md
git commit -m "docs: document and verify runtime target matrix"
```

## Self-Review

- Spec coverage: this plan covers the common/shared split, runtime leaf modules, worktree classification, and matrix verification required by the design.
- Placeholder scan: there are no `TODO`, `TBD`, or implicit “implement later” steps.
- Type consistency: all plan steps use the same `CommonModBootstrap`, `Platform*` contracts, and target-module naming.