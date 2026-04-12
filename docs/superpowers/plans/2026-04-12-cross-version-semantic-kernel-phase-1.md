# Cross-Version Semantic Kernel Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move item identity and block-entity persistence semantics out of version modules and make `v1_20_1-common` and `v1_21_1-common` consume a shared semantic model from `core`.

**Architecture:** Introduce a version-neutral model for computer item data and persisted computer identity in `core`, test it there, and then adapt each version-common module through tiny readers/writers around version-specific Minecraft APIs. Refactor current block and item code to depend on those adapters instead of touching `stack.tag`, `stack.computerDataTag`, or raw tag fields inline.

**Tech Stack:** Kotlin, Gradle, Architectury, Minecraft version-common modules, Kotlin test.

---

### Task 1: Add Shared Computer Item Data Model In `core`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemData.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemDataPolicy.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemDataPolicyTest.kt`

- [ ] **Step 1: Write the failing test for shared item identity behavior**

```kotlin
package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerItemDataPolicyTest {
    @Test
    fun preservesExistingIdentityWhenPresent() {
        val stored = ComputerItemData(computerId = 7, label = "alpha")

        assertEquals(
            stored,
            ComputerItemDataPolicy.resolvePlacedData(stored) { 99 },
        )
    }

    @Test
    fun allocatesMissingComputerIdDuringPlacement() {
        val resolved = ComputerItemDataPolicy.resolvePlacedData(
            ComputerItemData(computerId = null, label = "beta"),
        ) { 42 }

        assertEquals(42, resolved.computerId)
        assertEquals("beta", resolved.label)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.content.ComputerItemDataPolicyTest`
Expected: FAIL because `ComputerItemData` and `ComputerItemDataPolicy` do not exist yet.

- [ ] **Step 3: Add the minimal shared model and policy**

```kotlin
package ru.lazyhat.compukterkraft.core.content

data class ComputerItemData(
    val computerId: Int?,
    val label: String?,
)
```

```kotlin
package ru.lazyhat.compukterkraft.core.content

object ComputerItemDataPolicy {
    fun resolvePlacedData(
        stored: ComputerItemData,
        allocateComputerId: () -> Int,
    ): ComputerItemData =
        if (stored.computerId != null) {
            stored
        } else {
            stored.copy(computerId = allocateComputerId())
        }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.content.ComputerItemDataPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemData.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemDataPolicy.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerItemDataPolicyTest.kt
git commit -m "refactor: add shared computer item data policy"
```

### Task 2: Add Version-Local Item Data Adapters For 1.20.1 And 1.21.1

**Files:**
- Create: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdapters.kt`
- Create: `modules/v1_20_1/v1_20_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdaptersTest.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdapters.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdaptersTest.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt`

- [ ] **Step 1: Write the failing adapter tests in both version-common modules**

1.20.1 test:

```kotlin
package ru.lazyhat.compukterkraft.common.item

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerItemDataAdaptersTest {
    @Test
    fun roundTripsComputerItemDataThroughNbtBackedStack() {
        val stack = ItemStack(Items.STONE)
        val expected = ComputerItemData(computerId = 5, label = "alpha")

        stack.writeComputerItemData(expected)

        assertEquals(expected, stack.readComputerItemData())
    }
}
```

1.21.1 test:

```kotlin
package ru.lazyhat.compukterkraft.common.item

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerItemDataAdaptersTest {
    @Test
    fun roundTripsComputerItemDataThroughComponentBackedStack() {
        val stack = ItemStack(Items.STONE)
        val expected = ComputerItemData(computerId = 5, label = "alpha")

        stack.writeComputerItemData(expected)

        assertEquals(expected, stack.readComputerItemData())
    }
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `./gradlew :v1_20_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest`
Expected: FAIL because the adapter files and functions do not exist yet.

- [ ] **Step 3: Add minimal version-local adapters**

1.20.1 adapter shape:

```kotlin
package ru.lazyhat.compukterkraft.common.item

import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel

fun ItemStack.readComputerItemData(): ComputerItemData =
    ComputerItemData(
        computerId = tag?.computerID,
        label = tag?.computerLabel,
    )

fun ItemStack.writeComputerItemData(data: ComputerItemData) {
    val nbt = orCreateTag
    nbt.computerID = data.computerId
    nbt.computerLabel = data.label
}
```

1.21.1 adapter shape:

```kotlin
package ru.lazyhat.compukterkraft.common.item

import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import ru.lazyhat.compukterkraft.common.utils.computerDataTag
import ru.lazyhat.compukterkraft.common.utils.updateComputerData

fun ItemStack.readComputerItemData(): ComputerItemData =
    ComputerItemData(
        computerId = computerDataTag?.computerID,
        label = computerDataTag?.computerLabel,
    )

fun ItemStack.writeComputerItemData(data: ComputerItemData) {
    updateComputerData {
        computerID = data.computerId
        computerLabel = data.label
    }
}
```

- [ ] **Step 4: Run the adapter tests to verify they pass**

Run: `./gradlew :v1_20_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdapters.kt \
  modules/v1_20_1/v1_20_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdaptersTest.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdapters.kt \
  modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItemDataAdaptersTest.kt
git commit -m "refactor: add version item data adapters"
```

### Task 3: Refactor Block And Item Code To Consume The Adapter Surface

**Files:**
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/AbstractComputerItem.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItem.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/AbstractComputerItem.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItem.kt`

- [ ] **Step 1: Write a failing behavior test for shared placement semantics in one version-common module**

Create the 1.21.1 test at `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerPlacementDataTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.item

import ru.lazyhat.compukterkraft.core.content.ComputerItemData
import ru.lazyhat.compukterkraft.core.content.ComputerItemDataPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerPlacementDataTest {
    @Test
    fun missingIdGetsAllocatedWhileLabelIsPreserved() {
        val resolved = ComputerItemDataPolicy.resolvePlacedData(
            ComputerItemData(computerId = null, label = "alpha"),
        ) { 11 }

        assertEquals(ComputerItemData(computerId = 11, label = "alpha"), resolved)
    }
}
```

- [ ] **Step 2: Run the focused tests before refactoring**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerPlacementDataTest`
Expected: PASS for the policy itself, leaving the next refactor as a compile-only change. This establishes the intended behavior before consumer rewiring.

- [ ] **Step 3: Replace inline stack field access with adapters and shared policy**

In both `AbstractComputerBlock.kt` files, replace direct reads like these:

```kotlin
tile.computerID = stack.tag?.computerID
tile.label = stack.tag?.computerLabel
val resolvedComputerId = tile.computerID ?: ServerContext.allocateComputerId().also { tile.computerID = it }
```

and:

```kotlin
tile.computerID = stack.computerDataTag?.computerID
tile.label = stack.computerDataTag?.computerLabel
val resolvedComputerId = tile.computerID ?: ServerContext.allocateComputerId().also { tile.computerID = it }
```

with the shared model flow:

```kotlin
val resolvedData = ComputerItemDataPolicy.resolvePlacedData(
    stack.readComputerItemData(),
) { ServerContext.allocateComputerId() }

tile.computerID = resolvedData.computerId
tile.label = resolvedData.label
ServerContext.computerManager.ensureWorkspaceInitialized(checkNotNull(resolvedData.computerId))
```

In both `ComputerItem.kt` files, replace direct writes with:

```kotlin
fun create(id: Int?, label: String?): ItemStack =
    defaultInstance.also {
        it.writeComputerItemData(ComputerItemData(id, label))
    }
```

In both `AbstractComputerItem.kt` files, replace direct reads with `stack.readComputerItemData()`.

- [ ] **Step 4: Run focused compile and test verification**

Run: `./gradlew :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin :v1_20_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.item.ComputerItemDataAdaptersTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/AbstractComputerItem.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItem.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlock.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/AbstractComputerItem.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/item/ComputerItem.kt
git commit -m "refactor: route block placement through shared item data model"
```

### Task 4: Extract Shared Persistence Payload For Block Entities

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistenceData.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistencePolicy.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistencePolicyTest.kt`
- Create: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerPersistenceAdapters.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerPersistenceAdapters.kt`
- Modify: `modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt`

- [ ] **Step 1: Write the failing persistence policy test in `core`**

```kotlin
package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerPersistencePolicyTest {
    @Test
    fun buildsPersistencePayloadFromCurrentIdentity() {
        assertEquals(
            ComputerPersistenceData(computerId = 12, label = "delta"),
            ComputerPersistencePolicy.snapshot(computerId = 12, label = "delta"),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.content.ComputerPersistencePolicyTest`
Expected: FAIL because the persistence model does not exist yet.

- [ ] **Step 3: Add the shared persistence model and version adapters**

Shared model shape:

```kotlin
package ru.lazyhat.compukterkraft.core.content

data class ComputerPersistenceData(
    val computerId: Int?,
    val label: String?,
)

object ComputerPersistencePolicy {
    fun snapshot(computerId: Int?, label: String?): ComputerPersistenceData =
        ComputerPersistenceData(computerId = computerId, label = label)
}
```

1.20.1 adapter shape:

```kotlin
fun CompoundTag.writeComputerPersistence(data: ComputerPersistenceData) {
    computerID = data.computerId
    computerLabel = data.label
}

fun CompoundTag.readComputerPersistence(): ComputerPersistenceData =
    ComputerPersistenceData(
        computerId = computerID,
        label = computerLabel,
    )
```

1.21.1 adapter shape:

```kotlin
fun CompoundTag.writeComputerPersistence(data: ComputerPersistenceData) {
    computerID = data.computerId
    computerLabel = data.label
}

fun CompoundTag.readComputerPersistence(): ComputerPersistenceData =
    ComputerPersistenceData(
        computerId = computerID,
        label = computerLabel,
    )
```

- [ ] **Step 4: Refactor both `AbstractComputerBlockEntity.kt` files to use the shared snapshot and adapter functions**

Replace direct assignments such as:

```kotlin
tag.computerID = _computerID
tag.computerLabel = _label
```

and:

```kotlin
_computerID = tag.computerID
_label = tag.computerLabel
```

with:

```kotlin
tag.writeComputerPersistence(
    ComputerPersistencePolicy.snapshot(
        computerId = _computerID,
        label = _label,
    ),
)
```

and:

```kotlin
val persistence = tag.readComputerPersistence()
_computerID = persistence.computerId
_label = persistence.label
```

- [ ] **Step 5: Run focused verification**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.content.ComputerPersistencePolicyTest :v1_20_1-common:compileKotlin :v1_21_1-common:compileKotlin`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistenceData.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistencePolicy.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/content/ComputerPersistencePolicyTest.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerPersistenceAdapters.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/ComputerPersistenceAdapters.kt \
  modules/v1_20_1/v1_20_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/block/AbstractComputerBlockEntity.kt
git commit -m "refactor: extract shared computer persistence semantics"
```

## Self-Review Notes

- This plan intentionally scopes Phase 1 to item identity and persistence seams only.
- It does not decide repository topology.
- It produces a measurable reduction in cross-version duplication while preserving the current module graph.
- It keeps later branch-vs-trunk decisions open.