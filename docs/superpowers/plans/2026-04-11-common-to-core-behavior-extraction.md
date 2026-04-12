# Common-To-Core Behavior Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the remaining cross-version-neutral block, block-entity, and loot decision logic out of `v1_x_x-common` into `core`, while leaving Minecraft API translation and version drift in `common`.

**Architecture:** The loader-to-common migration is already complete. This plan starts from the current state where both `v1_20_1-common` and `v1_21_1-common` own the concrete Minecraft classes. The remaining step is to carve out pure policies and neutral models into `core`, then make the Minecraft-facing classes in both version-common modules delegate to those policies through thin adapter functions.

**Tech Stack:** Kotlin, Gradle, Architectury, Minecraft version-common modules, Kotlin test.

---

### Task 1: Introduce Neutral Block Policy Models In `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerContentModels.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockPolicy.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockPolicyTest.kt`

- [ ] **Step 1: Write the failing core test for block policy defaults and menu-open policy**

```kotlin
package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerBlockPolicyTest {
    @Test
    fun createsNorthFacingOffStateByDefault() {
        val defaults = ComputerBlockPolicy.defaultState()

        assertEquals(HorizontalFacingModel.NORTH, defaults.facing)
        assertEquals(ComputerVisualStateModel.OFF, defaults.state)
    }

    @Test
    fun placementFacingOpposesPlayerFacing() {
        assertEquals(HorizontalFacingModel.SOUTH, ComputerBlockPolicy.placementFacing(HorizontalFacingModel.NORTH))
        assertEquals(HorizontalFacingModel.WEST, ComputerBlockPolicy.placementFacing(HorizontalFacingModel.EAST))
    }

    @Test
    fun crouchingPlayerDoesNotOpenMenu() {
        assertTrue(ComputerBlockPolicy.shouldOpenMenu(isPlayerCrouching = false))
        assertFalse(ComputerBlockPolicy.shouldOpenMenu(isPlayerCrouching = true))
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.ComputerBlockPolicyTest"`
Expected: FAIL with unresolved references to `ComputerBlockPolicy`, `HorizontalFacingModel`, and `ComputerVisualStateModel`.

- [ ] **Step 3: Add the neutral models and policy in `core`**

```kotlin
package ru.lazyhat.compukterkraft.core.content

enum class HorizontalFacingModel {
    NORTH,
    EAST,
    SOUTH,
    WEST,
    ;

    fun opposite(): HorizontalFacingModel =
        when (this) {
            NORTH -> SOUTH
            EAST -> WEST
            SOUTH -> NORTH
            WEST -> EAST
        }
}

enum class ComputerVisualStateModel {
    OFF,
    ON,
}

data class ComputerBlockDefaults(
    val facing: HorizontalFacingModel,
    val state: ComputerVisualStateModel,
)
```

```kotlin
package ru.lazyhat.compukterkraft.core.content

object ComputerBlockPolicy {
    fun defaultState(): ComputerBlockDefaults =
        ComputerBlockDefaults(
            facing = HorizontalFacingModel.NORTH,
            state = ComputerVisualStateModel.OFF,
        )

    fun placementFacing(playerFacing: HorizontalFacingModel): HorizontalFacingModel = playerFacing.opposite()

    fun shouldOpenMenu(isPlayerCrouching: Boolean): Boolean = !isPlayerCrouching
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.ComputerBlockPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit the new block policy surface**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerContentModels.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockPolicy.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockPolicyTest.kt
git commit -m "refactor: add neutral computer block policy models"
```

### Task 2: Make Both Version-Common Block Classes Delegate To `core`

**Files:**
- Create: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockPolicyAdapters.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockPolicyAdapters.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlock.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlock.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt`
- Test: `:v1_20_1-common:compileKotlin`
- Test: `:v1_21_1-common:compileKotlin`

- [ ] **Step 1: Add version-local adapters from core models to Minecraft types**

Create the same adapter file in both version-common modules:

```kotlin
package ru.lazyhat.compukterkraft.common.block

import net.minecraft.core.Direction
import ru.lazyhat.compukterkraft.core.content.ComputerVisualStateModel
import ru.lazyhat.compukterkraft.core.content.HorizontalFacingModel

internal fun HorizontalFacingModel.toMinecraftDirection(): Direction =
    when (this) {
        HorizontalFacingModel.NORTH -> Direction.NORTH
        HorizontalFacingModel.EAST -> Direction.EAST
        HorizontalFacingModel.SOUTH -> Direction.SOUTH
        HorizontalFacingModel.WEST -> Direction.WEST
    }

internal fun Direction.toFacingModel(): HorizontalFacingModel =
    when (this) {
        Direction.NORTH -> HorizontalFacingModel.NORTH
        Direction.EAST -> HorizontalFacingModel.EAST
        Direction.SOUTH -> HorizontalFacingModel.SOUTH
        Direction.WEST -> HorizontalFacingModel.WEST
        else -> error("Only horizontal directions are supported: $this")
    }

internal fun ComputerVisualStateModel.toMinecraftState(): ComputerState =
    when (this) {
        ComputerVisualStateModel.OFF -> ComputerState.OFF
        ComputerVisualStateModel.ON -> ComputerState.ON
    }

internal fun ComputerState.toStateModel(): ComputerVisualStateModel =
    when (this) {
        ComputerState.OFF -> ComputerVisualStateModel.OFF
        ComputerState.ON -> ComputerVisualStateModel.ON
    }
```

- [ ] **Step 2: Run one compile target to verify the adapters compile before wiring**

Run: `./gradlew :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Rewire both version-common block classes to use the core policy**

In both `ComputerBlock.kt` files, replace the hard-coded default state and placement logic:

```kotlin
private val defaults = ComputerBlockPolicy.defaultState()

init {
    registerDefaultState(
        defaultBlockState()
            .setValue(facing, defaults.facing.toMinecraftDirection())
            .setValue(state, defaults.state.toMinecraftState()),
    )
}

override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
    defaultBlockState().setValue(
        facing,
        ComputerBlockPolicy.placementFacing(context.horizontalDirection.toFacingModel()).toMinecraftDirection(),
    )
```

In both `AbstractComputerBlock.kt` files, gate menu opening through the core policy while preserving the version-specific Minecraft API entrypoint shape:

```kotlin
if (ComputerBlockPolicy.shouldOpenMenu(player.isCrouching)) {
    (level.getBlockEntity(pos) as? AbstractComputerBlockEntity)?.run {
        ifServerSide(level)
            ?.let { computer ->
                val serverComputer = computer.getOrCreateServerComputer()
                ModObjects.openComputerMenu(
                    player as ServerPlayer,
                    computer,
                    ComputerContainerData(serverComputer, getItem(computer)),
                )
                return InteractionResult.sidedSuccess(level.isClientSide)
            }
    }
}
```

Only the policy decision moves to `core`. Keep `use()` versus `useWithoutItem()` and other Minecraft API differences in each version-common file.

- [ ] **Step 4: Run both version-common compile targets**

Run: `./gradlew :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit the block-policy delegation**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockPolicyAdapters.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlock.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockPolicyAdapters.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlock.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt
git commit -m "refactor: delegate block policy from version common to core"
```

### Task 3: Extract Block-Entity Lifecycle Policy Into `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockEntityPolicy.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockEntityPolicyTest.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockEntity.kt`
- Test: `:core:test`
- Test: `:v1_20_1-common:compileKotlin`
- Test: `:v1_21_1-common:compileKotlin`

- [ ] **Step 1: Write the failing core test for block-entity lifecycle policy**

```kotlin
package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerBlockEntityPolicyTest {
    @Test
    fun updatesVisualStateOnlyWhenItChanges() {
        assertFalse(
            ComputerBlockEntityPolicy.shouldUpdateVisualState(
                current = ComputerVisualStateModel.OFF,
                next = ComputerVisualStateModel.OFF,
            ),
        )
        assertTrue(
            ComputerBlockEntityPolicy.shouldUpdateVisualState(
                current = ComputerVisualStateModel.OFF,
                next = ComputerVisualStateModel.ON,
            ),
        )
    }

    @Test
    fun onlyPersistsChangedNonNullIdentityValues() {
        assertFalse(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = "alpha"))
        assertFalse(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = null))
        assertTrue(ComputerBlockEntityPolicy.shouldPersistLabel(current = "alpha", requested = "beta"))

        assertFalse(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = 1))
        assertFalse(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = null))
        assertTrue(ComputerBlockEntityPolicy.shouldPersistComputerId(current = 1, requested = 2))
    }

    @Test
    fun resolvesExistingIdWithoutAllocating() {
        assertEquals(7, ComputerBlockEntityPolicy.resolveComputerId(current = 7) { 99 })
        assertEquals(99, ComputerBlockEntityPolicy.resolveComputerId(current = null) { 99 })
    }

    @Test
    fun skipsServerTickWhenClientSideOrIdMissing() {
        assertFalse(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = true, computerId = 1))
        assertFalse(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = false, computerId = null))
        assertTrue(ComputerBlockEntityPolicy.shouldRunServerTick(levelIsClientSide = false, computerId = 1))
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.ComputerBlockEntityPolicyTest"`
Expected: FAIL with unresolved reference to `ComputerBlockEntityPolicy`.

- [ ] **Step 3: Add the block-entity policy object in `core`**

```kotlin
package ru.lazyhat.compukterkraft.core.content

object ComputerBlockEntityPolicy {
    fun shouldUpdateVisualState(
        current: ComputerVisualStateModel,
        next: ComputerVisualStateModel,
    ): Boolean = current != next

    fun shouldPersistLabel(current: String?, requested: String?): Boolean =
        requested != null && current != requested

    fun shouldPersistComputerId(current: Int?, requested: Int?): Boolean =
        requested != null && current != requested

    fun resolveComputerId(current: Int?, allocate: () -> Int): Int = current ?: allocate()

    fun shouldRunServerTick(levelIsClientSide: Boolean, computerId: Int?): Boolean =
        !levelIsClientSide && computerId != null

    fun desiredVisualState(isComputerOn: Boolean): ComputerVisualStateModel =
        if (isComputerOn) ComputerVisualStateModel.ON else ComputerVisualStateModel.OFF
}
```

- [ ] **Step 4: Make both version-common block-entity layers delegate to the core policy**

In both `ComputerBlockEntity.kt` files, rewrite `updateBlockState(...)` to delegate through the core model:

```kotlin
override fun updateBlockState(newState: ComputerState) {
    val currentState = blockState.getValue(ComputerBlock.state).toStateModel()
    val nextState = newState.toStateModel()

    if (!ComputerBlockEntityPolicy.shouldUpdateVisualState(currentState, nextState)) return

    level?.setBlock(
        blockPos,
        blockState.setValue(ComputerBlock.state, nextState.toMinecraftState()),
        Block.UPDATE_CLIENTS,
    )
}
```

In both `AbstractComputerBlockEntity.kt` files, delegate the pure decisions only:

```kotlin
value
    ?.ifServerSide(level)
    ?.takeIf { ComputerBlockEntityPolicy.shouldPersistLabel(_label, value) }
    ?.let {
        _label = value
        _computerID
            ?.let(ServerContext.computerManager::get)
            ?.updateLabel(value)
        updateBlock()
    }
```

```kotlin
value
    ?.ifServerSide(level)
    ?.takeIf { ComputerBlockEntityPolicy.shouldPersistComputerId(_computerID, value) }
    ?.let {
        _computerID = value
        updateBlock()
    }
```

```kotlin
fun serverTick() {
    if (!ComputerBlockEntityPolicy.shouldRunServerTick(level?.isClientSide ?: true, _computerID)) return
    val computer = getOrCreateServerComputer()
    computer.serverTick()
    updateBlockState(
        ComputerBlockEntityPolicy.desiredVisualState(computer.isOn).toMinecraftState(),
    )
}
```

```kotlin
val resolvedComputerId = ComputerBlockEntityPolicy.resolveComputerId(_computerID) {
    ServerContext.allocateComputerId().also { allocatedComputerId ->
        computerID = allocatedComputerId
        ServerContext.computerManager.ensureWorkspaceInitialized(allocatedComputerId)
    }
}
```

Do not move `ServerContext`, `ServerComputer`, NBT load/save, or Minecraft API calls into `core`. Only move the decision rules.

- [ ] **Step 5: Run the verification set**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.ComputerBlockEntityPolicyTest" :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit the block-entity policy extraction**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockEntityPolicy.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerBlockEntityPolicyTest.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerBlockEntity.kt
git commit -m "refactor: extract block entity policy into core"
```

### Task 4: Extract Loot Predicate Policy Into `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/LootConditionPolicy.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/LootConditionPolicyTest.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/BlockNamedEntityLootCondition.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/HasComputerIdLootCondition.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/PlayerCreativeLootCondition.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/BlockNamedEntityLootCondition.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/HasComputerIdLootCondition.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/PlayerCreativeLootCondition.kt`
- Test: `:core:test`
- Test: `:v1_20_1-common:compileKotlin`
- Test: `:v1_21_1-common:compileKotlin`

- [ ] **Step 1: Write the failing core test for loot predicate policy**

```kotlin
package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LootConditionPolicyTest {
    @Test
    fun reportsPresenceOfStoredIdentity() {
        assertFalse(LootConditionPolicy.hasComputerId(null))
        assertTrue(LootConditionPolicy.hasComputerId(42))
    }

    @Test
    fun reportsPresenceOfCustomName() {
        assertFalse(LootConditionPolicy.hasCustomName(false))
        assertTrue(LootConditionPolicy.hasCustomName(true))
    }

    @Test
    fun reportsCreativeModeFromBooleanCapability() {
        assertFalse(LootConditionPolicy.isCreativePlayer(false))
        assertTrue(LootConditionPolicy.isCreativePlayer(true))
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.LootConditionPolicyTest"`
Expected: FAIL with unresolved reference to `LootConditionPolicy`.

- [ ] **Step 3: Add the pure loot predicate policy in `core`**

```kotlin
package ru.lazyhat.compukterkraft.core.content

object LootConditionPolicy {
    fun hasComputerId(computerId: Int?): Boolean = computerId != null

    fun hasCustomName(hasCustomName: Boolean): Boolean = hasCustomName

    fun isCreativePlayer(hasInstabuildAbility: Boolean): Boolean = hasInstabuildAbility
}
```

- [ ] **Step 4: Make all version-common loot conditions delegate to the core policy**

Update all six loot condition wrappers.

Representative replacements:

```kotlin
override fun test(lootContext: LootContext): Boolean =
    lootContext.getParamOrNull(LootContextParams.BLOCK_ENTITY)?.let { tile ->
        tile is ComputerBlockEntity && LootConditionPolicy.hasComputerId(tile.computerID)
    } ?: false
```

```kotlin
override fun test(lootContext: LootContext): Boolean =
    lootContext.getParamOrNull(LootContextParams.BLOCK_ENTITY)?.let { tile ->
        tile is Nameable && LootConditionPolicy.hasCustomName(tile.hasCustomName())
    } ?: false
```

```kotlin
override fun test(lootContext: LootContext): Boolean =
    lootContext.getParamOrNull(LootContextParams.THIS_ENTITY)?.let { entity ->
        entity is Player && LootConditionPolicy.isCreativePlayer(entity.abilities.instabuild)
    } ?: false
```

- [ ] **Step 5: Run the verification set**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.content.LootConditionPolicyTest" :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit the loot-policy extraction**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/LootConditionPolicy.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/LootConditionPolicyTest.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/BlockNamedEntityLootCondition.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/HasComputerIdLootCondition.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/PlayerCreativeLootCondition.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/BlockNamedEntityLootCondition.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/HasComputerIdLootCondition.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/loot/PlayerCreativeLootCondition.kt
git commit -m "refactor: extract loot predicate policy into core"
```

### Task 5: Document The New `common -> core` Boundary And Run Final Verification

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-04-11-residual-cross-version-extraction-design.md`
- Modify: `docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md`
- Test: `:core:test`
- Test: common compile matrix
- Test: loader compile matrix

- [ ] **Step 1: Update architecture docs so they describe the narrower phase-5 extraction boundary**

Add the following rule to `docs/ARCHITECTURE.md` near the ownership section:

```markdown
- `core` owns neutral content policy and decision logic such as default block state, menu-open gating, block-entity state-transition rules, and loot predicates
- `v1_x_x-common` owns Minecraft API translation from those policies into `Block`, `BlockEntity`, `Menu`, `Screen`, and loot-condition implementations
```

Update `docs/superpowers/specs/2026-04-11-residual-cross-version-extraction-design.md` so Phase 5 explicitly says the extraction is limited to pure policy slices and neutral models, not wholesale movement of Minecraft classes.

Update `docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md` so the “Follow-Up Boundary Decision” section no longer says no additional block/loot behavior extraction is part of the boundary. Replace it with a statement that the remaining follow-up is pure policy extraction from `common` into `core`.

- [ ] **Step 2: Run the full targeted verification matrix**

Run: `./gradlew :core:test :v1_20_1-common:compileKotlin :v1_20_1-fabric:compileKotlin :v1_20_1-forge:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Re-run the loader inventory audit to verify the extraction did not leak behavior back down**

Run:

```bash
find modules/v1_20_1/v1_20_1-fabric/src/main/kotlin -name '*.kt' | sort
find modules/v1_20_1/v1_20_1-forge/src/main/kotlin -name '*.kt' | sort
find modules/v1_21_1/v1_21_1-fabric/src/main/kotlin -name '*.kt' | sort
find modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin -name '*.kt' | sort
```

Expected: only bootstrap, hooks, registries, network handlers, and tiny loader shims remain.

- [ ] **Step 4: Commit the documentation and verification finish line**

```bash
git add docs/ARCHITECTURE.md \
  docs/superpowers/specs/2026-04-11-residual-cross-version-extraction-design.md \
  docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md
git commit -m "docs: record common to core policy boundary"
```