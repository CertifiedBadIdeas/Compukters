# Residual Cross-Version Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the unfinished migration so that loader modules contain only bootstrap/binding logic, concrete Minecraft-facing shared content lives in `v1_x_x-common`, and behavior that is not hard-bound to Minecraft API moves into `core`.

**Architecture:** Introduce late-bound Minecraft-typed references in each `v1_x_x-common` module so concrete blocks, block entities, loot conditions, and saved-data helpers can stop depending on loader wrapper types (`Supplier`, `RegistryObject`, `DeferredHolder`). After that, move the concrete classes from loader modules into `common`, then extract pure behavior and policy from those classes into `core`.

**Tech Stack:** Kotlin, Gradle, Architectury, Fabric, Forge, NeoForge, Minecraft version-specific modules, Kotlin test.

---

### Task 1: Add Late-Bound Version References In `v1_21_1-common`

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/binding/ModObjects.kt`
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Test: compile target `:v1_21_1-common:compileKotlin`

- [ ] **Step 1: Add the new common binding object with the full late-bound reference surface**

Create `ModObjects.kt` with the minimal late-bound references:

```kotlin
package compukterkraft.mod.binding

import compukterkraft.mod.block.AbstractComputerBlockEntity
import compukterkraft.mod.block.ComputerBlockEntity
import compukterkraft.mod.data.ComputerContainerData
import compukterkraft.mod.menu.ComputerMenuWithoutInventory
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType

object ModObjects {
  lateinit var computerBlockEntityType: () -> BlockEntityType<*>
  lateinit var computerMenuType: () -> MenuType<ComputerMenuWithoutInventory>
  lateinit var openComputerMenu: (ServerPlayer, AbstractComputerBlockEntity, ComputerContainerData) -> Unit
  lateinit var blockNamedEntityLootConditionType: () -> LootItemConditionType
  lateinit var hasComputerIdLootConditionType: () -> LootItemConditionType
  lateinit var playerCreativeLootConditionType: () -> LootItemConditionType
}
```

At this stage, do not force a `v1_21_1-common` consumer yet. The first real consumer appears in Task 2 when `ComputerBlockEntity.kt` moves into `common`. Right now the task is only to define and wire the late-bound reference surface.

Use the widened `BlockEntityType<*>` here intentionally. The exact `BlockEntityType<ComputerBlockEntity>` shape only becomes valid after `ComputerBlockEntity` itself has moved into `v1_21_1-common` in Task 2.

- [ ] **Step 2: Run compile to verify the new binding object is valid before wiring**

Run: `./gradlew :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Wire `ModObjects` from both loaders**

In both loader entrypoints, initialize the common references after `ModRegistry.register(...)` and before any code path can instantiate the moved classes.

Fabric shape:

```kotlin
ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED }
ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER }
ModObjects.openComputerMenu = { player, computer, menuData -> /* Fabric screen-opening bridge */ }
ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED }
ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID }
ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE }
```

NeoForge shape:

```kotlin
ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED.get() }
ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
ModObjects.openComputerMenu = { player, computer, menuData -> /* NeoForge screen-opening bridge */ }
ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }
```

- [ ] **Step 4: Run compile to verify the new reference layer works**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/binding/ModObjects.kt \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/CompukterKraftMod.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/CompukterKraftMod.kt
git commit -m "refactor: add 1.21.1 common mod object bindings"
```

### Task 2: Move The 1.21.1 Block Layer Into `v1_21_1-common`

**Files:**
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/AbstractComputerBlock.kt`
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/AbstractComputerBlockEntity.kt`
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlock.kt`
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block/ComputerBlockEntity.kt`
- Modify: both 1.21.1 loader `ModRegistry.kt` files
- Test: compile target `:v1_21_1-common:compileKotlin`

- [ ] **Step 1: Write the failing compile target by moving `ComputerBlockEntity.kt` to common first**

Move the current Fabric version of `ComputerBlockEntity.kt` into `v1_21_1-common` and replace direct menu registry access with `ModObjects.computerMenuType()`.

Expected replacement in `createMenu(...)`:

```kotlin
override fun createMenu(
    syncID: Int,
    inventory: Inventory,
    player: Player,
): AbstractContainerMenu = ComputerMenuWithoutInventory(ModObjects.computerMenuType(), syncID, inventory, this)
```

- [ ] **Step 2: Run compile to expose the next dependency blockers**

Run: `./gradlew :v1_21_1-common:compileKotlin`
Expected: FAIL in `ComputerBlock`, `AbstractComputerBlock`, and any remaining direct registry-wrapper references.

- [ ] **Step 3: Move the rest of the block layer and replace loader-wrapper accesses**

Apply the same pattern to the remaining files:

- `ComputerBlock` should use `ModObjects.computerBlockEntityType()` for the constructor/binding needs currently coming from `Supplier` or `DeferredHolder`
- `AbstractComputerBlock` should stop depending on loader wrapper types and route screen-opening through `ModObjects.openComputerMenu` when the loader APIs differ
- `AbstractComputerBlockEntity` should be moved whole, leaving only thin loader-only lifecycle hooks behind if any exist

When a method differs only because of loader wrapper access, keep the method in `common` and route the data dependency through `ModObjects`.

- [ ] **Step 4: Shrink loader `ModRegistry.kt` to binding-only responsibilities**

After the move, `ModRegistry` should still declare and register the loader-side objects, but block classes should now be instantiated from `v1_21_1-common` classes instead of loader-local ones.

- [ ] **Step 5: Run focused verification**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod
git commit -m "refactor: move 1.21.1 block layer into version common"
```

### Task 3: Add Late-Bound References And Move The 1.20.1 Block Layer

**Files:**
- Create: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/binding/ModObjects.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block/AbstractComputerBlock.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block/AbstractComputerBlockEntity.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block/ComputerBlock.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block/ComputerBlockEntity.kt`
- Modify: `modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Modify: `modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod/CompukterKraftMod.kt`
- Test: compile targets for 1.20.1

- [ ] **Step 1: Add the 1.20.1 `ModObjects.kt` with the same binding shape**

Use the same object as in 1.21.1, adapted to 1.20.1 imports:

```kotlin
object ModObjects {
  lateinit var computerBlockEntityType: () -> BlockEntityType<*>
  lateinit var computerMenuType: () -> MenuType<ComputerMenuWithoutInventory>
  lateinit var openComputerMenu: (ServerPlayer, AbstractComputerBlockEntity, ComputerContainerData) -> Unit
  lateinit var blockNamedEntityLootConditionType: () -> LootItemConditionType
  lateinit var hasComputerIdLootConditionType: () -> LootItemConditionType
  lateinit var playerCreativeLootConditionType: () -> LootItemConditionType
}
```

- [ ] **Step 2: Wire the bindings in Fabric and Forge**

Fabric wiring:

```kotlin
ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED }
ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER }
ModObjects.openComputerMenu = { player, computer, menuData -> /* Fabric 1.20.1 screen-opening bridge */ }
ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED }
ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID }
ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE }
```

Forge wiring:

```kotlin
ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED.get() }
ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
ModObjects.openComputerMenu = { player, computer, menuData -> /* Forge 1.20.1 screen-opening bridge */ }
ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }
```

- [ ] **Step 3: Move the 1.20.1 block layer into common**

Move:

- `AbstractComputerBlock.kt`
- `AbstractComputerBlockEntity.kt`
- `ComputerBlock.kt`
- `ComputerBlockEntity.kt`

Replace all loader-wrapper accesses with `ModObjects` references or direct Minecraft types.

If Forge still needs a loader-only `onChunkUnloaded()` override after the move, preserve it as a tiny Forge-local shim subclass rather than keeping the full block-entity implementation in the loader module.

- [ ] **Step 4: Run version verification**

Run: `./gradlew :v1_20_1-common:compileKotlin :v1_20_1-fabric:compileKotlin :v1_20_1-forge:compileKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/binding \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block \
  modules/v1_20_1/v1_20_1-fabric/src/main/kotlin/ck/mod \
  modules/v1_20_1/v1_20_1-forge/src/main/kotlin/ck/mod
git commit -m "refactor: move 1.20.1 block layer into version common"
```

### Task 4: Move Loot Conditions And Normalize `ComputerIdentitySavedData`

**Files:**
- Create or move to: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/loot/*.kt`
- Create or move to: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/loot/*.kt`
- Modify or move: `modules/v1_21_1/*/src/main/kotlin/ck/mod/context/ComputerIdentitySavedData.kt`
- Modify: loader `CompukterKraftMod.kt` files if a saved-data binding is introduced
- Test: version compile targets

- [ ] **Step 1: Move the loot condition classes into `common` for both versions**

Move the following files for each version family and replace registry access with `ModObjects`:

- `BlockNamedEntityLootCondition.kt`
- `HasComputerIdLootCondition.kt`
- `PlayerCreativeLootCondition.kt`

Representative replacement:

```kotlin
override fun getType(): LootItemConditionType = ModObjects.hasComputerIdLootConditionType()
```

- [ ] **Step 2: Normalize 1.21.1 `ComputerIdentitySavedData` ownership**

If the only remaining difference is the loader-specific `SavedData.Factory` construction shape, split the class into:

- a `common` implementation file that owns the state and logic
- a tiny loader-local factory/binding shim if required by NeoForge/Fabric API differences

The `allocateComputerId()` and persistence logic should not remain duplicated in loader modules.

- [ ] **Step 3: Run both version-family compile targets**

Run: `./gradlew :v1_20_1-common:compileKotlin :v1_20_1-fabric:compileKotlin :v1_20_1-forge:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/loot \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/loot \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/context \
  modules/v1_21_1/v1_21_1-fabric/src/main/kotlin/ck/mod/context \
  modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ck/mod/context
git commit -m "refactor: centralize loot and saved data ownership"
```

### Task 5: Extract Block And Loot Behavior From `common` Into `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ck/mod/content/ComputerBlockBehavior.kt`
- Create: `modules/core/src/main/kotlin/ck/mod/content/ComputerBlockEntityBehavior.kt`
- Create: `modules/core/src/main/kotlin/ck/mod/content/LootConditionBehavior.kt`
- Create: `modules/core/src/test/kotlin/ck/mod/content/ComputerBlockBehaviorTest.kt`
- Modify: moved block and loot classes in both `v1_x_x-common` modules
- Test: `:core:test` plus version compile targets

- [ ] **Step 1: Write the failing tests for the first behavior slice**

Create `ComputerBlockBehaviorTest.kt`:

```kotlin
package compukterkraft.mod.content

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerBlockBehaviorTest {
    @Test
    fun updatesOnlyWhenPowerStateChanges() {
        assertFalse(ComputerBlockEntityBehavior.shouldUpdateState("off", "off"))
        assertTrue(ComputerBlockEntityBehavior.shouldUpdateState("off", "on"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests compukterkraft.mod.content.ComputerBlockBehaviorTest`
Expected: FAIL because the behavior objects do not exist yet.

- [ ] **Step 3: Add the minimal behavior objects**

Create `ComputerBlockEntityBehavior.kt`:

```kotlin
package compukterkraft.mod.content

object ComputerBlockEntityBehavior {
    fun shouldUpdateState(current: String, next: String): Boolean = current != next
}
```

Create `ComputerBlockBehavior.kt`:

```kotlin
package compukterkraft.mod.content

object ComputerBlockBehavior {
    fun defaultFacing(): String = "north"
    fun defaultPowerState(): String = "off"
}
```

Create `LootConditionBehavior.kt` for any predicate logic that can be expressed without Minecraft API classes.

- [ ] **Step 4: Make moved Minecraft classes delegate to `core` behavior**

Representative examples:

```kotlin
val defaultFacing = ComputerBlockBehavior.defaultFacing()
```

```kotlin
if (!ComputerBlockEntityBehavior.shouldUpdateState(current.name, newState.name)) return
```

Only extract decisions and comparisons. Keep Minecraft object creation, block-state mutation, and registry access in `common`.

- [ ] **Step 5: Run verification**

Run: `./gradlew :core:test :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ck/mod/content \
  modules/core/src/test/kotlin/ck/mod/content \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/block \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ck/mod/loot \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/block \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ck/mod/loot
git commit -m "refactor: extract block and loot behavior into core"
```

### Task 6: Final Loader Audit And Documentation Fixup

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-04-11-residual-cross-version-extraction-design.md`
- Modify: `docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md`
- Test: final verification matrix

- [ ] **Step 1: Re-audit loader modules after the moves**

Run:

```bash
find modules/v1_20_1/v1_20_1-fabric/src/main/kotlin -name '*.kt' | sort
find modules/v1_20_1/v1_20_1-forge/src/main/kotlin -name '*.kt' | sort
find modules/v1_21_1/v1_21_1-fabric/src/main/kotlin -name '*.kt' | sort
find modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin -name '*.kt' | sort
```

Expected: only bootstrap, hooks, registries, network handlers, and tiny loader shims remain.

- [ ] **Step 2: Update architecture docs to match the new end state**

Add or update the rule:

```markdown
- `core` owns behavior and policy whenever Minecraft API is not required
- `v1_x_x-common` owns concrete Minecraft-facing version code
- loader modules own only bootstrap and binding glue
```

- [ ] **Step 3: Run the final verification matrix**

Run: `./gradlew :core:test :v1_20_1-common:compileKotlin :v1_20_1-fabric:compileKotlin :v1_20_1-forge:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-fabric:compileKotlin :v1_21_1-neoforge:compileKotlin :v1_21_11-common:compileKotlin :v1_21_11-fabric:compileKotlin :v1_21_11-neoforge:compileKotlin`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add ARCHITECTURE.md \
  docs/superpowers/specs/2026-04-11-residual-cross-version-extraction-design.md \
  docs/superpowers/plans/2026-04-10-cross-version-duplicate-audit.md
git commit -m "docs: finalize cross-version ownership boundaries"
```