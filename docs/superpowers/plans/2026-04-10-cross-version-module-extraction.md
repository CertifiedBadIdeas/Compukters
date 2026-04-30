# Cross-Version Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move shared Minecraft-facing code out of loader leaf modules, strengthen `core` contracts, and turn `v1_x_x-common` into the primary compatibility layer for cross-version support.

**Architecture:** Keep concrete Minecraft classes such as `Block`, `BlockEntity`, `Menu`, and `Screen` in `v1_x_x-common`, but progressively make them thin wrappers over shared behavior, descriptors, and orchestration defined in `core`. Keep loader leaf modules focused on bootstrap, event hookup, service binding, and loader-specific network registration.

**Tech Stack:** Kotlin, Gradle, Architectury, Fabric, Forge, NeoForge, Minecraft version-specific modules, Kotlin test.

---

### Task 1: Replace String Registration With Descriptors In `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ck/mod/bootstrap/CommonContentModels.kt`
- Modify: `modules/core/src/main/kotlin/ck/mod/bootstrap/CommonContentDescriptors.kt`
- Modify: `modules/core/src/main/kotlin/ck/mod/platform/api/PlatformBlockRegistrar.kt`
- Modify: `modules/core/src/main/kotlin/ck/mod/platform/api/PlatformMenuRegistrar.kt`
- Modify: `modules/core/src/main/kotlin/ck/mod/bootstrap/CommonModBootstrap.kt`
- Test: `modules/core/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt`

- [ ] **Step 1: Write the failing test for descriptor-based bootstrap**

```kotlin
class CommonModBootstrapTest {
    @Test
    fun registersDescriptorBasedContentThroughPlatformPorts() {
        val blocks = RecordingBlockRegistrar()
        val menus = RecordingMenuRegistrar()
        val network = RecordingNetworkRegistrar()
        val clientHooks = RecordingClientHooks()

        CommonModBootstrap.registerCommon(blocks, menus, network, clientHooks)

        assertEquals(listOf(CommonBlockDescriptor.ComputerAdvanced), blocks.blocks)
        assertEquals(listOf(CommonMenuDescriptor.Computer), menus.menus)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests compukterkraft.mod.bootstrap.CommonModBootstrapTest`
Expected: FAIL because descriptor types do not exist yet and registrars still accept `String`.

- [ ] **Step 3: Add minimal descriptor model and port changes**

```kotlin
package compukterkraft.mod.bootstrap

enum class CommonBlockDescriptor(
    val id: String,
) {
    ComputerAdvanced("computer_advanced"),
}

enum class CommonMenuDescriptor(
    val id: String,
) {
    Computer("computer"),
}
```

```kotlin
package compukterkraft.mod.platform.api

import compukterkraft.mod.bootstrap.CommonBlockDescriptor

interface PlatformBlockRegistrar {
    fun registerBlock(descriptor: CommonBlockDescriptor)
}
```

```kotlin
package compukterkraft.mod.platform.api

import compukterkraft.mod.bootstrap.CommonMenuDescriptor

interface PlatformMenuRegistrar {
    fun registerMenu(descriptor: CommonMenuDescriptor)
}
```

- [ ] **Step 4: Update bootstrap to use descriptors**

```kotlin
object CommonModBootstrap {
    fun registerCommon(
        blocks: PlatformBlockRegistrar,
        menus: PlatformMenuRegistrar,
        network: PlatformNetworkRegistrar,
        clientHooks: PlatformClientHooks,
    ) {
        blocks.registerBlock(CommonBlockDescriptor.ComputerAdvanced)
        menus.registerMenu(CommonMenuDescriptor.Computer)

        CommonNetworkProtocol.serverboundChannels.forEach(network::registerServerbound)
        CommonNetworkProtocol.clientboundChannels.forEach(network::registerClientbound)
        clientHooks.registerClientScreens()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:test --tests compukterkraft.mod.bootstrap.CommonModBootstrapTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ck/mod/bootstrap/CommonContentModels.kt \
  modules/core/src/main/kotlin/ck/mod/bootstrap/CommonContentDescriptors.kt \
  modules/core/src/main/kotlin/ck/mod/platform/api/PlatformBlockRegistrar.kt \
  modules/core/src/main/kotlin/ck/mod/platform/api/PlatformMenuRegistrar.kt \
  modules/core/src/main/kotlin/ck/mod/bootstrap/CommonModBootstrap.kt \
  modules/core/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt
git commit -m "refactor: bootstrap common content with descriptors"
```

### Task 2: Move Version-Shared Content Out Of Loader Leaf Modules

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/ModRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/ModRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/content/CommonContentRegistry.kt`
- Move or create equivalents under `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/content/`
- Test: `modules/core/src/test/kotlin/ck/mod/bootstrap/CommonModBootstrapTest.kt`

- [ ] **Step 1: Write the failing integration test for version-common content registration entrypoint**

```kotlin
class CommonContentRegistryTest {
    @Test
    fun registersComputerContentForThe121Family() {
        val registry = RecordingContentRegistry()

        CommonContentRegistry.registerAll(registry)

        assertTrue(registry.calls.contains("block:computer_advanced"))
        assertTrue(registry.calls.contains("menu:computer"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests compukterkraft.mod.content.CommonContentRegistryTest`
Expected: FAIL because `CommonContentRegistry` does not exist yet.

- [ ] **Step 3: Introduce a version-common content registry facade**

```kotlin
package compukterkraft.mod.content

object CommonContentRegistry {
    fun registerAll(registry: VersionContentRegistry) {
        registry.registerBlock("computer_advanced")
        registry.registerBlockEntity("computer_advanced")
        registry.registerItem("computer_advanced")
        registry.registerMenu("computer")
    }
}
```

- [ ] **Step 4: Move shared content packages from leaf modules into `v1_21_1-common`**

Move these packages from both leaf modules into `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/` and update imports:

```text
block/
item/
menu/
computer/
context/
data/
loot/
gui/screen/
```

When moving code, keep leaf modules limited to bootstrap classes such as:

```text
CompukterKraftMod.kt
ClientRegistry.kt
FabricCommonHooks.kt
ForgeCommonHooks.kt
NetworkHandler.kt
```

- [ ] **Step 5: Update leaf entrypoints to delegate to version-common registries**

```kotlin
class CompukterKraftMod : ModInitializer {
    override fun onInitialize() {
        LOGGER.info { "$MOD_ID has started!" }
        CommonContentRegistry.registerAll(FabricRegistryAdapter)
        NetworkHandler.setup()
        FabricCommonHooks.register()
    }
}
```

- [ ] **Step 6: Run focused compile verification**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod
git commit -m "refactor: move 1.21.1 shared content into version common"
```

### Task 3: Extract Shared Block And Block Entity Behavior Into `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ck/mod/content/ComputerBlockBehavior.kt`
- Create: `modules/core/src/main/kotlin/ck/mod/content/ComputerBlockEntityBehavior.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlock.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlockEntity.kt`
- Test: `modules/core/src/test/kotlin/ck/mod/content/ComputerBlockBehaviorTest.kt`

- [ ] **Step 1: Write the failing test for shared block behavior**

```kotlin
class ComputerBlockBehaviorTest {
    @Test
    fun createsNorthFacingOffStateByDefault() {
        val state = ComputerBlockBehavior.defaultState()

        assertEquals(DirectionModel.North, state.facing)
        assertEquals(ComputerPowerState.Off, state.power)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests compukterkraft.mod.content.ComputerBlockBehaviorTest`
Expected: FAIL because behavior model does not exist yet.

- [ ] **Step 3: Add behavior objects in `core`**

```kotlin
package compukterkraft.mod.content

object ComputerBlockBehavior {
    fun defaultState(): ComputerBlockStateModel =
        ComputerBlockStateModel(
            facing = DirectionModel.North,
            power = ComputerPowerState.Off,
        )
}
```

```kotlin
package compukterkraft.mod.content

object ComputerBlockEntityBehavior {
    fun shouldUpdateState(current: ComputerPowerState, next: ComputerPowerState): Boolean = current != next
}
```

- [ ] **Step 4: Make Minecraft classes delegate instead of own logic**

```kotlin
class ComputerBlock(...) : AbstractComputerBlock<ComputerBlockEntity>(type, properties) {
    init {
        val defaultState = ComputerBlockBehavior.defaultState()
        registerDefaultState(
            defaultBlockState()
                .setValue(facing, defaultState.toMinecraftFacing())
                .setValue(state, defaultState.toMinecraftPowerState()),
        )
    }
}
```

```kotlin
class ComputerBlockEntity(...) : AbstractComputerBlockEntity(type, pos, state, family) {
    override fun updateBlockState(newState: ComputerState) {
        val current = blockState.getValue(ComputerBlock.state)
        if (!ComputerBlockEntityBehavior.shouldUpdateState(current.toModel(), newState.toModel())) return

        level?.setBlock(blockPos, blockState.setValue(ComputerBlock.state, newState), Block.UPDATE_CLIENTS)
    }
}
```

- [ ] **Step 5: Run tests and compile verification**

Run: `./gradlew :core:test --tests compukterkraft.mod.content.ComputerBlockBehaviorTest :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ck/mod/content \
  modules/core/src/test/kotlin/ck/mod/content/ComputerBlockBehaviorTest.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlock.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlockEntity.kt
git commit -m "refactor: extract shared computer block behavior"
```

### Task 4: Move Runtime And Context Ownership Into Version-Common

**Files:**
- Modify: `modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod/context/ServerContext.kt`
- Modify: `modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod/context/ServerContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/context/ServerContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/context/ServerContext.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/runtime/`
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/runtime/`
- Test: existing runtime tests under `modules/*/src/test/kotlin/**/ComputerProgramSupportTest.kt`

- [ ] **Step 1: Write the failing compile target by moving one runtime class declaration**

```kotlin
package compukterkraft.mod.runtime

object CommonServerContext
```

Use the move to force leaf modules to import runtime/context from version-common instead of defining their own copies.

- [ ] **Step 2: Run one compile target to verify imports break first**

Run: `./gradlew :v1_20_1-fabric:compileKotlin`
Expected: FAIL with unresolved imports until the runtime/context classes are moved and imports are updated.

- [ ] **Step 3: Move server runtime/context classes into version-common**

Move and normalize these categories first:

```text
context/ComputerManager.kt
context/ServerContext.kt
context/ComputerIdentitySavedData.kt
computer/ServerComputer.kt
data/ComputerContainerData.kt
```

The target shape is:

```text
v1_x_x-common/src/main/kotlin/ck/mod/runtime/
v1_x_x-common/src/main/kotlin/ck/mod/context/
v1_x_x-common/src/main/kotlin/ck/mod/computer/
```

- [ ] **Step 4: Keep loader hooks limited to lifecycle wiring**

```kotlin
object FabricCommonHooks {
    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            ServerContext.create(server)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            ServerContext.close()
        }
    }
}
```

- [ ] **Step 5: Run runtime-focused tests**

Run: `./gradlew :v1_20_1-common:test :v1_21_1-common:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod \
  modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod \
  modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod
git commit -m "refactor: move runtime and context ownership to version common"
```

### Task 5: Consolidate Network Message Models In Version-Common

**Files:**
- Modify: `modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod/network/**`
- Modify: `modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod/network/**`
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/network/**`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/network/**`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/network/`
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/network/`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ck/mod/platform/NetworkHandlerPayloadIdTest.kt`

- [ ] **Step 1: Write the failing test for shared message ids in version-common**

```kotlin
class NetworkMessageCatalogTest {
    @Test
    fun exposesStableProtocolIdsFromVersionCommon() {
        assertEquals("computer_terminal", NetworkMessages.ComputerTerminal.id)
        assertEquals("computer_workspace", NetworkMessages.ComputerWorkspace.id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests compukterkraft.mod.network.NetworkMessageCatalogTest`
Expected: FAIL if message catalog still lives only in leaf modules.

- [ ] **Step 3: Move protocol model classes into version-common**

Move these classes first when they do not use loader-only registration APIs:

```text
network/NetworkMessages.kt
network/NetworkMessage.kt
network/MessageType.kt
network/client/*.kt
network/server/*Message.kt
network/text/*.kt
```

Keep these classes loader-local:

```text
platform/NetworkHandler.kt
network/server/ServerNetworking.kt
network/ClientNetworking.kt
```

- [ ] **Step 4: Rewire loader network handlers to register moved models**

```kotlin
object NetworkHandler {
    fun setup() {
        CommonNetworkMessages.serverbound.forEach(::registerServerbound)
        CommonNetworkMessages.clientbound.forEach(::registerClientbound)
    }
}
```

- [ ] **Step 5: Run network tests and compile verification**

Run: `./gradlew :v1_21_1-common:test --tests compukterkraft.mod.network.NetworkMessageCatalogTest :v1_21_1-neoforge:test --tests compukterkraft.mod.platform.NetworkHandlerPayloadIdTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/network \
  modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod/network \
  modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod/network \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/network \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/network \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/network
git commit -m "refactor: move network message models into version common"
```

### Task 6: Audit Remaining Duplicates And Roll The Pattern Forward

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-04-10-cross-version-module-extraction-design.md`
- Create: `docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md`
- Modify: version-specific utility files under `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/utils/`
- Modify: version-specific utility files under `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/utils/`

- [ ] **Step 1: Write the audit checklist document**

```markdown
# Cross-Version Duplicate Audit

- classify each duplicate as loader-only, version-only, or accidental
- record target module for each duplicate
- record why a duplicate is intentionally kept
```

- [ ] **Step 2: Run a duplicate inventory search**

Run: `rg --files modules/v1_20_1 modules/v1_21_1 | rg 'ComputerBlock|ComputerBlockEntity|ServerComputer|NetworkMessages|NBTUtls|LevelUtils|BufferUtils|CommandUtils'`
Expected: list of duplicate candidates to classify.

- [ ] **Step 3: Move only truly stable helpers upward**

If a helper is byte-for-byte equivalent and not tied to version drift, move it into `modules/core/src/main/kotlin/ck/mod/` or a new shared helper package. Example candidates include:

```text
NBTUtls.kt
LevelUtils.kt
BufferUtils.kt
CommandUtils.kt
BlockEntityUtils.kt
```

Do not move helpers upward if their signatures are expected to diverge in future Minecraft versions.

- [ ] **Step 4: Update architecture documentation**

Add a short section to `ARCHITECTURE.md` documenting the enforced rule:

```markdown
- `core` owns shared behavior and descriptors
- `v1_x_x-common` owns Minecraft-facing version adapters and classes
- loader leaf modules own only bootstrap and loader API wiring
```

- [ ] **Step 5: Run the verification matrix**

Run: `./gradlew test :v1_20_1-common:compileKotlin :v1_20_1-fabric:compileKotlin :v1_20_1-forge:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin :v1_21_11-common:compileKotlin :v1_21_11-fabric:compileKotlin :v1_21_11-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/2026-04-10-cross-version-module-extraction-design.md \
  docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md modules/core/src/main/kotlin/ck/mod \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod
git commit -m "docs: codify cross-version module ownership rules"
```