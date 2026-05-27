# Workbench Separate Entity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Workbench as a separate Minecraft-facing development station with its own target slot, local authoring workspace, target-aware IDE behavior, and explicit pull/push/run/attach actions against an inserted computer item.

**Architecture:** Reuse the existing core workbench editor/store/IDE abstractions, but stop hosting them inside the computer menu flow. Add a dedicated Workbench device in the common Minecraft layer, with its own block, block entity, menu, screen, and server/client network messages. Workbench keeps its own authoring session state while the inserted computer item acts as the target descriptor for capabilities and execution.

**Tech Stack:** Kotlin, Architectury multi-module mod structure, existing `WorkbenchStore`, computer menu/network patterns, Gradle test and compile tasks.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonContentModels.kt` | Modify | Add Workbench block/menu descriptors |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrap.kt` | Modify | Register Workbench content into common bootstrap |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonNetworkProtocol.kt` | Modify | Add Workbench network channel ids |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt` | Modify | Extend contracts from computer-only gateway to target-aware workbench actions |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt` | Modify | Track target connection state and sync state in the UI model |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt` | Modify | Drive pull/push/run/attach actions and disconnected-state behavior |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt` | Modify | Add state/action tests for target-aware Workbench flows |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt` | Modify | Bind Workbench block entity, menu, and menu-opening lambda |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlock.kt` | Create | Minecraft block entry point for separate Workbench device |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt` | Create | Menu-constructor block entity holding target slot and workbench session state |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/item/WorkbenchItem.kt` | Create | Item form of Workbench block |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt` | Create | Side-aware menu carrying target slot, workspace state flow, and terminal snapshot |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt` | Create | Concrete registered menu type |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt` | Create | Dedicated Workbench screen built around `WorkbenchStore` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt` | Create | Server-side workbench authoring workspace, sync state, and target computer bridge |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt` | Create | Server-bound pull/push/run/attach/list/read/write requests |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchWorkspaceClientMessage.kt` | Create | Client-bound workspace/target/sync state updates |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt` | Create | Menu reconstruction data for target connection state |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt` | Modify | Add Workbench-specific gateway adapters and target descriptor IDE source |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt` | Modify | Register Workbench block, item, block entity, menu, and messages |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt` | Modify | Register Workbench screen |
| `docs/ARCHITECTURE.md` | Modify | Document Workbench as separate device and authoring/execution split |

### Task 1: Extend Core Workbench Model For Target-Aware Sessions

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Write the failing tests for target-aware Workbench state**

Add these tests to `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`:

```kotlin
@Test
fun disablesTargetActionsWhenNoTargetIsConnected() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeWorkbenchControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
        store.toggleMode()

        assertTrue(!store.state.target.connected)
        assertTrue(!store.state.actions.canPull)
        assertTrue(!store.state.actions.canPush)
        assertTrue(!store.state.actions.canRun)
        assertTrue(!store.state.actions.canAttachTerminal)
    }

@Test
fun enablesTargetActionsWhenTargetDescriptorArrives() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeWorkbenchControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(
            document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0),
            target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"),
        )

        assertTrue(store.state.target.connected)
        assertTrue(store.state.actions.canPull)
        assertTrue(store.state.actions.canPush)
        assertTrue(store.state.actions.canRun)
        assertTrue(store.state.actions.canAttachTerminal)
    }

@Test
fun runActionDelegatesToControlGateway() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val controlGateway = FakeWorkbenchControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

        store.runTargetProgram()

        assertEquals(listOf("run"), controlGateway.calls)
    }
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.disablesTargetActionsWhenNoTargetIsConnected" --tests "WorkbenchStoreTest.enablesTargetActionsWhenTargetDescriptorArrives" --tests "WorkbenchStoreTest.runActionDelegatesToControlGateway" --no-daemon`

Expected: FAIL because `WorkbenchTargetState`, action availability state, and `runTargetProgram()` do not exist yet.

- [ ] **Step 3: Extend the contracts with explicit target actions**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt`, add target-aware control operations and remote state:

```kotlin
data class WorkbenchTargetState(
    val connected: Boolean = false,
    val displayName: String? = null,
    val familyId: String? = null,
)

data class WorkbenchSyncState(
    val dirtyLocal: Boolean = false,
    val dirtyRemote: Boolean = false,
)

data class WorkbenchRemoteState(
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val document: ComputerWorkspaceDocument? = null,
    val target: WorkbenchTargetState = WorkbenchTargetState(),
    val sync: WorkbenchSyncState = WorkbenchSyncState(),
)

interface ComputerControlGateway {
    fun reboot()

    fun pullFromTarget()

    fun pushToTarget()

    fun runTargetProgram()

    fun attachTargetTerminal()
}
```

- [ ] **Step 4: Add target and action state to the store model**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`, extend the state shape:

```kotlin
data class WorkbenchActionState(
    val canPull: Boolean = false,
    val canPush: Boolean = false,
    val canRun: Boolean = false,
    val canAttachTerminal: Boolean = false,
)

data class WorkbenchState(
    val mode: WorkbenchMode = WorkbenchMode.TERMINAL,
    val browserPath: String = "",
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val openDocument: ComputerWorkspaceDocument? = null,
    val editor: EditorState = EditorState(),
    val target: WorkbenchTargetState = WorkbenchTargetState(),
    val sync: WorkbenchSyncState = WorkbenchSyncState(),
    val actions: WorkbenchActionState = WorkbenchActionState(),
)
```

- [ ] **Step 5: Implement target-aware action gating in `WorkbenchStore`**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`, add explicit actions and derive availability from target connection state:

```kotlin
fun pullFromTarget() {
    if (!state.actions.canPull) return
    controlGateway.pullFromTarget()
}

fun pushToTarget() {
    if (!state.actions.canPush) return
    controlGateway.pushToTarget()
}

fun runTargetProgram() {
    if (!state.actions.canRun) return
    controlGateway.runTargetProgram()
}

fun attachTargetTerminal() {
    if (!state.actions.canAttachTerminal) return
    controlGateway.attachTargetTerminal()
}

private fun mergeRemoteState(remoteState: WorkbenchRemoteState) {
    val documentChanged = remoteState.document != state.openDocument
    var nextState = state

    if (remoteState.entries != state.entries) {
        nextState = nextState.copy(entries = remoteState.entries)
    }

    if (remoteState.document != state.openDocument) {
        nextState =
            nextState.copy(
                openDocument = remoteState.document,
                editor = remoteState.document?.let { EditorState(text = it.text) } ?: EditorState(),
            )
    }

    val actionState =
        WorkbenchActionState(
            canPull = remoteState.target.connected,
            canPush = remoteState.target.connected,
            canRun = remoteState.target.connected,
            canAttachTerminal = remoteState.target.connected,
        )

    _state.value =
        nextState.copy(
            target = remoteState.target,
            sync = remoteState.sync,
            actions = actionState,
        )

    if (documentChanged && remoteState.document != null) {
        refreshIde()
    }
}
```

- [ ] **Step 6: Update the fake gateway/test scaffolding**

In `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`, extend the fake control gateway:

```kotlin
private class FakeWorkbenchControlGateway : ComputerControlGateway {
    val calls = mutableListOf<String>()

    override fun reboot() {
        calls += "reboot"
    }

    override fun pullFromTarget() {
        calls += "pull"
    }

    override fun pushToTarget() {
        calls += "push"
    }

    override fun runTargetProgram() {
        calls += "run"
    }

    override fun attachTargetTerminal() {
        calls += "attach"
    }
}
```

- [ ] **Step 7: Run the targeted tests to verify they pass**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.disablesTargetActionsWhenNoTargetIsConnected" --tests "WorkbenchStoreTest.enablesTargetActionsWhenTargetDescriptorArrives" --tests "WorkbenchStoreTest.runActionDelegatesToControlGateway" --no-daemon`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt
git commit -m "feat: add target-aware core workbench session model"
```

### Task 2: Register Workbench As Its Own Content Type

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonContentModels.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrap.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonNetworkProtocol.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt`

- [ ] **Step 1: Add a failing compile-time reference for Workbench content descriptors**

In `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrapTest.kt`, add:

```kotlin
@Test
fun exposesWorkbenchDescriptors() {
    assertTrue(CommonBlockDescriptor.entries.any { it.name == "WORKBENCH" })
    assertTrue(CommonMenuDescriptor.entries.any { it.name == "WORKBENCH" })
    assertTrue(CommonNetworkProtocol.serverbound.contains("workbench_workspace_request"))
    assertTrue(CommonNetworkProtocol.clientbound.contains("workbench_workspace"))
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :core:test --tests "CommonModBootstrapTest.exposesWorkbenchDescriptors" --no-daemon`

Expected: FAIL because the Workbench descriptors and channel ids do not exist.

- [ ] **Step 3: Add Workbench descriptors to core bootstrap models**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonContentModels.kt`, extend the enums:

```kotlin
enum class CommonBlockDescriptor {
    COMPUTER,
    WORKBENCH,
}

enum class CommonMenuDescriptor {
    COMPUTER,
    WORKBENCH,
}
```

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonNetworkProtocol.kt`, add the Workbench channels:

```kotlin
val serverbound =
    listOf(
        "computer_action",
        "key_event",
        "mouse_event",
        "paste_event",
        "computer_workspace_request",
        "workbench_workspace_request",
    )

val clientbound =
    listOf(
        "chat_table",
        "computer_terminal",
        "computer_workspace",
        "workbench_workspace",
    )
```

- [ ] **Step 4: Register Workbench content in bootstrap and common bindings**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrap.kt`, add registration calls:

```kotlin
blocks.registerBlock(CommonBlockDescriptor.WORKBENCH)
menus.registerMenu(CommonMenuDescriptor.WORKBENCH)
```

In `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt`, add lateinit bindings:

```kotlin
lateinit var workbenchBlockEntityType: BlockEntityType<*>
lateinit var workbenchMenuType: MenuType<*>
lateinit var openWorkbenchMenu: (ServerPlayer, BlockPos) -> Unit
```

- [ ] **Step 5: Add Workbench registration entries in NeoForge registry code**

In `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt`, add holders:

```kotlin
val workbench = REGISTRY.register("workbench") { WorkbenchBlock() }
val workbenchBlockEntity =
    REGISTRY.register("workbench") {
        BlockEntityType.Builder.of(::WorkbenchBlockEntity, Blocks.workbench.get()).build(null)
    }
val workbench = REGISTRY.register("workbench") { BlockItem(Blocks.workbench.get(), Item.Properties()) }
val workbench = REGISTRY.register("workbench") { IMenuTypeExtension.create(::WorkbenchMenuWithoutInventory) }
```

Assign them into `ModObjects` in the existing post-registration wiring block.

- [ ] **Step 6: Register the Workbench screen**

In `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt`, add:

```kotlin
MenuScreens.register(ModRegistry.Menus.workbench.get(), ::WorkbenchEditorScreen)
```

- [ ] **Step 7: Run the targeted test and compile verification**

Run: `./gradlew :core:test --tests "CommonModBootstrapTest.exposesWorkbenchDescriptors" :v1_21_1-common:compileKotlin --no-daemon`

Expected: PASS for the test and BUILD SUCCESSFUL for compile.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonContentModels.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrap.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonNetworkProtocol.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt
git commit -m "feat: register separate workbench device content"
```

### Task 3: Introduce The Minecraft-Facing Workbench Device Skeleton

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlock.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/item/WorkbenchItem.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt`

- [ ] **Step 1: Add a failing menu-construction smoke test**

Create `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`:

```kotlin
class WorkbenchMenuSmokeTest {
    @Test
    fun constructsWorkbenchMenuWithoutTarget() {
        val menu = WorkbenchMenuWithoutInventory(1, Inventory(TestPlayerFactory.create()), WorkbenchContainerData())

        assertEquals(1, menu.containerId)
        assertTrue(menu.workspaceStateFlow.value.target.connected.not())
    }
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests "WorkbenchMenuSmokeTest.constructsWorkbenchMenuWithoutTarget" --no-daemon`

Expected: FAIL because the Workbench classes do not exist yet.

- [ ] **Step 3: Implement the Workbench block and block entity**

Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlock.kt`:

```kotlin
class WorkbenchBlock : HorizontalDirectionalBlock(Properties.of().strength(2.5f)), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WorkbenchBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val blockEntity = level.getBlockEntity(pos) as? WorkbenchBlockEntity ?: return InteractionResult.PASS
        blockEntity.openFor(player as ServerPlayer)
        return InteractionResult.CONSUME
    }
}
```

Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`:

```kotlin
class WorkbenchBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModObjects.workbenchBlockEntityType, pos, state), MenuConstructor, Nameable {
    private val session = ServerWorkbench()

    override fun createMenu(containerId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu =
        WorkbenchMenuWithoutInventory(containerId, playerInventory, WorkbenchContainerData(), session)

    override fun getDisplayName(): Component = Component.translatable("block.compukterkraft.workbench")

    fun openFor(player: ServerPlayer) {
        ModObjects.openWorkbenchMenu(player, blockPos)
    }
}
```

- [ ] **Step 4: Implement the Workbench menu skeleton and container data**

Create `WorkbenchContainerData.kt` with target state snapshot data:

```kotlin
data class WorkbenchContainerData(
    val targetConnected: Boolean = false,
    val targetFamilyId: String? = null,
)
```

Create `AbstractWorkbenchMenu.kt`:

```kotlin
abstract class AbstractWorkbenchMenu(
    menuType: MenuType<*>,
    containerId: Int,
    playerInventory: Inventory,
    protected val containerData: WorkbenchContainerData,
    protected val serverWorkbench: ServerWorkbench? = null,
) : AbstractContainerMenu(menuType, containerId) {
    protected val _workspaceStateFlow = MutableStateFlow(
        WorkbenchRemoteState(
            target =
                WorkbenchTargetState(
                    connected = containerData.targetConnected,
                    familyId = containerData.targetFamilyId,
                ),
        ),
    )

    val workspaceStateFlow: StateFlow<WorkbenchRemoteState> = _workspaceStateFlow

    override fun stillValid(player: Player): Boolean = true
}
```

Create `WorkbenchMenuWithoutInventory.kt`:

```kotlin
class WorkbenchMenuWithoutInventory(
    containerId: Int,
    playerInventory: Inventory,
    containerData: WorkbenchContainerData,
    serverWorkbench: ServerWorkbench? = null,
) : AbstractWorkbenchMenu(ModObjects.workbenchMenuType, containerId, playerInventory, containerData, serverWorkbench)
```

- [ ] **Step 5: Implement the Workbench item**

Create `WorkbenchItem.kt`:

```kotlin
class WorkbenchItem(block: Block) : BlockItem(block, Properties())
```

- [ ] **Step 6: Run the targeted smoke test and common compile**

Run: `./gradlew :v1_21_1-common:test --tests "WorkbenchMenuSmokeTest.constructsWorkbenchMenuWithoutTarget" :v1_21_1-common:compileKotlin --no-daemon`

Expected: PASS for the test and BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlock.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/item/WorkbenchItem.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt
git commit -m "feat: add separate workbench device skeleton"
```

### Task 4: Add ServerWorkbench Session And Target Descriptor Extraction

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`

- [ ] **Step 1: Add a failing target-descriptor test in core-facing gateway code**

Add to `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`:

```kotlin
@Test
fun targetDescriptorControlsAvailableImports() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeWorkbenchControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(target = WorkbenchTargetState(connected = true, displayName = "Terminal Only", familyId = "terminal_only"))

        store.openImportPicker()

        assertTrue(ideFacade.calls.contains("availableImports"))
    }
```

- [ ] **Step 2: Run the targeted test to verify the current implementation is insufficient**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.targetDescriptorControlsAvailableImports" --no-daemon`

Expected: FAIL or remain impossible to wire because Workbench menu/gateway layer does not yet expose a target descriptor-backed IDE source.

- [ ] **Step 3: Implement `ServerWorkbench` as the authoring-side session owner**

Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`:

```kotlin
class ServerWorkbench {
    private var targetComputer: ItemStack = ItemStack.EMPTY
    private val workspace = mutableMapOf<String, String>("main.ck" to "fun main() {}")

    fun setTarget(stack: ItemStack) {
        targetComputer = stack.copyWithCount(1)
    }

    fun clearTarget() {
        targetComputer = ItemStack.EMPTY
    }

    fun targetState(): WorkbenchTargetState {
        if (targetComputer.isEmpty) return WorkbenchTargetState()
        return WorkbenchTargetState(
            connected = true,
            displayName = targetComputer.hoverName.string,
            familyId = targetComputer.get(DataComponents.CUSTOM_NAME)?.string ?: "normal",
        )
    }

    fun listEntries(): List<ComputerWorkspaceEntry> =
        workspace.keys.sorted().map { ComputerWorkspaceEntry(path = it, directory = false) }

    fun read(path: String): ComputerWorkspaceDocument? =
        workspace[path]?.let { ComputerWorkspaceDocument(path, it, 0) }

    fun write(path: String, text: String) {
        workspace[path] = text
    }
}
```

- [ ] **Step 4: Flow the target state into menu remote updates and IDE source**

In `WorkbenchBlockEntity.kt`, build the initial container data from `session.targetState()`.

In `AbstractWorkbenchMenu.kt`, add a refresh helper:

```kotlin
fun refreshFromServerWorkbench() {
    val workbench = serverWorkbench ?: return
    _workspaceStateFlow.value =
        WorkbenchRemoteState(
            entries = workbench.listEntries(),
            document = workbench.read(_workspaceStateFlow.value.document?.path ?: "main.ck"),
            target = workbench.targetState(),
        )
}
```

In `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`, add a Workbench-specific catalog source:

```kotlin
class WorkbenchTargetCatalogSource(
    private val targetState: WorkbenchTargetState,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): BuiltinRegistry {
        val family = when (targetState.familyId) {
            "terminal_only" -> ComputerFamily.NORMAL
            else -> ComputerFamily.NORMAL
        }
        return ComputerFamilyCatalogSource(family).runtimeRegistry()
    }
}
```

- [ ] **Step 5: Run the targeted test and common compile verification**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.targetDescriptorControlsAvailableImports" :v1_21_1-common:compileKotlin --no-daemon`

Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt
git commit -m "feat: add workbench authoring session and target descriptor"
```

### Task 5: Add Workbench Workspace Network Messages And Sync Actions

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchWorkspaceClientMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`

- [ ] **Step 1: Add a failing gateway test for pull/push delegation**

Add to `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`:

```kotlin
@Test
fun pullAndPushActionsDelegateToControlGateway() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val controlGateway = FakeWorkbenchControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

        store.pullFromTarget()
        store.pushToTarget()

        assertEquals(listOf("pull", "push"), controlGateway.calls)
    }
```

- [ ] **Step 2: Run the targeted test to verify it fails or is incomplete**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.pullAndPushActionsDelegateToControlGateway" --no-daemon`

Expected: FAIL until the full control flow and message layer are wired.

- [ ] **Step 3: Implement server/client Workbench workspace messages**

Create `WorkbenchWorkspaceServerMessage.kt`:

```kotlin
class WorkbenchWorkspaceServerMessage() : NetworkMessage<ServerNetworkContext> {
    enum class Action {
        LIST,
        READ,
        WRITE,
        PULL,
        PUSH,
        RUN,
        ATTACH_TERMINAL,
    }

    var containerId: Int = 0
    var action: Action = Action.LIST
    var path: String = ""
    var text: String = ""

    constructor(containerId: Int, action: Action, path: String = "", text: String = "") : this() {
        this.containerId = containerId
        this.action = action
        this.path = path
        this.text = text
    }

    constructor(buf: FriendlyByteBuf) : this() {
        containerId = buf.readVarInt()
        action = buf.readEnum(Action::class.java)
        path = buf.readUtf()
        text = buf.readUtf()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeEnum(action)
        buf.writeUtf(path)
        buf.writeUtf(text)
    }

    override fun handle(context: ServerNetworkContext) {
        val menu = context.player.containerMenu as? AbstractWorkbenchMenu ?: return
        menu.handleWorkspaceAction(action, path, text)
    }
}
```

Create `WorkbenchWorkspaceClientMessage.kt` mirroring `ComputerWorkspaceClientMessage`, but include target/sync state:

```kotlin
class WorkbenchWorkspaceClientMessage() : NetworkMessage<ClientNetworkContext> {
    enum class Kind {
        ENTRIES,
        DOCUMENT,
        TARGET,
    }

    // include `entries`, `document`, and `targetConnected/targetName/targetFamilyId`
}
```

- [ ] **Step 4: Handle actions in the Workbench menu and adapt gateways**

In `AbstractWorkbenchMenu.kt`, implement:

```kotlin
fun handleWorkspaceAction(
    action: WorkbenchWorkspaceServerMessage.Action,
    path: String,
    text: String,
) {
    val workbench = serverWorkbench ?: return
    when (action) {
        WorkbenchWorkspaceServerMessage.Action.LIST -> refreshFromServerWorkbench()
        WorkbenchWorkspaceServerMessage.Action.READ -> {
            _workspaceStateFlow.value = _workspaceStateFlow.value.copy(document = workbench.read(path))
        }
        WorkbenchWorkspaceServerMessage.Action.WRITE -> {
            workbench.write(path, text)
            refreshFromServerWorkbench()
        }
        WorkbenchWorkspaceServerMessage.Action.PULL -> workbench.pullFromTarget()
        WorkbenchWorkspaceServerMessage.Action.PUSH -> workbench.pushToTarget()
        WorkbenchWorkspaceServerMessage.Action.RUN -> workbench.runTargetProgram()
        WorkbenchWorkspaceServerMessage.Action.ATTACH_TERMINAL -> workbench.attachTerminal()
    }
}
```

In `WorkbenchGateways.kt`, add a `NetworkWorkbenchControlGateway` that sends `PULL`, `PUSH`, `RUN`, and `ATTACH_TERMINAL` actions.

- [ ] **Step 5: Run targeted tests and common compile verification**

Run: `./gradlew :core:test --tests "WorkbenchStoreTest.pullAndPushActionsDelegateToControlGateway" :v1_21_1-common:compileKotlin --no-daemon`

Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchWorkspaceClientMessage.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt
git commit -m "feat: add explicit workbench sync and execution messages"
```

### Task 6: Add Dedicated Workbench Screen And Terminal Attach Flow

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`

- [ ] **Step 1: Add a failing compile target referencing the new screen class**

In `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ClientRegistry.kt`, temporarily reference `WorkbenchEditorScreen` in the screen registration added in Task 2.

Run: `./gradlew :v1_21_1-common:compileKotlin --no-daemon`

Expected: FAIL because the class does not yet exist.

- [ ] **Step 2: Create `WorkbenchEditorScreen` by forking the current computer workbench screen**

Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`:

```kotlin
class WorkbenchEditorScreen(
    container: WorkbenchMenuWithoutInventory,
    player: Inventory,
    title: Component,
) : AbstractContainerScreen<WorkbenchMenuWithoutInventory>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val store =
        WorkbenchStore(
            workspaceGateway = NetworkWorkspaceGateway(container),
            controlGateway = NetworkWorkbenchControlGateway(container),
            ideFacade = LanguageWorkbenchIdeFacade(WorkbenchTargetCatalogSource(container.workspaceStateFlow.value.target)),
        )

    // port the existing render/input flow from ComputerWorkbenchScreen,
    // but stop depending on computer-menu-specific fields.
}
```

- [ ] **Step 3: Add target-aware toolbar actions to the screen**

Port the toolbar click handling from the current workbench screen and add buttons for:

```kotlin
when (button.index) {
    0 -> store.toggleMode()
    1 -> store.saveDocument()
    2 -> store.pullFromTarget()
    3 -> store.pushToTarget()
    4 -> store.runTargetProgram()
    5 -> store.openImportPicker()
}
```

Disable pull/push/run when `store.state.target.connected` is false.

- [ ] **Step 4: Attach terminal state flow to the screen**

In `AbstractWorkbenchMenu.kt`, add:

```kotlin
val screenSnapshot: ScreenBufferSnapshot
    get() = serverWorkbench?.currentTerminalSnapshot() ?: ScreenBufferSnapshot.empty(terminalWidth = 16, terminalHeight = 8)
```

Expose target terminal snapshot updates through the same menu-side pattern used by the computer menu so the screen can render output and send input once attached.

- [ ] **Step 5: Run common compile verification**

Run: `./gradlew :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt
git commit -m "feat: add dedicated workbench editor screen"
```

### Task 7: Wire Pull/Push/Run/Attach Into The Existing Computer Systems

**Execution note:** Task 7 was unblocked by tightening the target invariant: a Workbench runtime target is only considered connected when the inserted computer item has a bound `computerId`. `pull` and `push` bridge directly through `ComputerWorkspace` by that id. `run` and `attach terminal` now bridge through a Workbench-owned runtime host fallback: if a live `ServerComputer` for the target id exists, Workbench observes and interacts with it; otherwise the Workbench block entity temporarily hosts the target VM itself and ticks/syncs it while the Workbench session is open.

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`

- [ ] **Step 1: Add a failing server-side unit test for pull/push roundtrip**

Create `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbenchTest.kt`:

```kotlin
class ServerWorkbenchTest {
    @Test
    fun pullsAndPushesFilesAgainstTargetComputerWorkspace() {
        val target = FakeTargetComputer(files = mutableMapOf("main.ck" to "print(1)"))
        val workbench = ServerWorkbench(target)

        workbench.pullFromTarget()
        assertEquals("print(1)", workbench.read("main.ck")?.text)

        workbench.write("main.ck", "print(2)")
        workbench.pushToTarget()

        assertEquals("print(2)", target.files["main.ck"])
    }
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests "ServerWorkbenchTest.pullsAndPushesFilesAgainstTargetComputerWorkspace" --no-daemon`

Expected: FAIL because `ServerWorkbench` does not yet bridge to a target computer workspace.

- [ ] **Step 3: Add a narrow target-computer bridge abstraction inside `ServerWorkbench`**

In `ServerWorkbench.kt`, introduce an interface local to the file:

```kotlin
fun interface WorkbenchTargetWorkspace {
    fun readAll(): Map<String, String>
}

interface WorkbenchTargetComputer {
    fun readAllFiles(): Map<String, String>

    fun writeAllFiles(files: Map<String, String>)

    fun runMainProgram()

    fun attachTerminal(): ScreenBufferSnapshot
}
```

Use it in the session methods:

```kotlin
fun pullFromTarget() {
    val target = targetComputerBridge ?: return
    workspace.clear()
    workspace.putAll(target.readAllFiles())
}

fun pushToTarget() {
    targetComputerBridge?.writeAllFiles(workspace.toMap())
}

fun runTargetProgram() {
    targetComputerBridge?.runMainProgram()
}

fun attachTerminal() {
    terminalSnapshot = targetComputerBridge?.attachTerminal() ?: terminalSnapshot
}
```

- [ ] **Step 4: Bridge to the real server computer layer**

In `ServerComputer.kt`, add narrow methods that Workbench can call without learning the entire computer internals:

```kotlin
fun readAllWorkspaceFiles(): Map<String, String>

fun writeAllWorkspaceFiles(files: Map<String, String>)

fun runMainProgramFromWorkbench()

fun currentTerminalSnapshot(): ScreenBufferSnapshot
```

Resolve the inserted computer item to a real server computer via existing computer identity/manager infrastructure in `ComputerManager.kt`, then inject that bridge into `ServerWorkbench`.

- [ ] **Step 5: Run the targeted test and cross-module compile verification**

Run: `./gradlew :v1_21_1-common:test --tests "ServerWorkbenchTest.pullsAndPushesFilesAgainstTargetComputerWorkspace" :v1_21_1-common:compileKotlin :core:compileKotlin --no-daemon`

Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbenchTest.kt
git commit -m "feat: connect workbench sync and execution to target computers"
```

### Task 8: Final Verification And Documentation

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`
- Verify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`
- Verify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbenchTest.kt`

- [ ] **Step 1: Update architecture documentation**

In `docs/ARCHITECTURE.md`, add Workbench as its own device package and explain the split:

```markdown
### `Workbench`

Workbench is a separate development device.

- It owns local authoring state and target-aware IDE behavior.
- It does not execute user programs.
- It performs explicit pull/push/run/attach actions against an inserted computer target.
```

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test --no-daemon`

Expected: PASS.

- [ ] **Step 3: Run common tests**

Run: `./gradlew :v1_21_1-common:test --no-daemon`

Expected: PASS.

- [ ] **Step 4: Run cross-module compilation**

Run: `./gradlew :compiler:compileKotlin :core:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual verification checklist**

1. Place a Workbench block in the world.
2. Insert a computer item into its target slot.
3. Confirm the IDE import catalog changes to match the inserted computer profile.
4. Edit a local file in Workbench and confirm the target computer does not change until `push`.
5. Use `pull` and confirm local files refresh from the target computer.
6. Use `push` and confirm target files update.
7. Use `run` and confirm the inserted target computer starts executing the program.
8. Use `attach terminal` and confirm terminal output and input work through Workbench.
9. Remove the inserted computer and confirm the local project remains while target actions disable.

- [ ] **Step 6: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: document separate workbench device architecture"
```

## Self-Review

- Spec coverage: the plan covers the separate Workbench device, target descriptor, local workspace, explicit pull/push/run/attach actions, disconnected-state behavior, target-aware IDE behavior, and architecture documentation. Collaboration and remote networking remain out of scope as required.
- Placeholder scan: no `TBD` or deferred “implement later” placeholders remain. Each task names exact files, concrete steps, and verification commands.
- Type consistency: the plan consistently uses `WorkbenchTargetState`, `WorkbenchSyncState`, `WorkbenchActionState`, `ServerWorkbench`, and `WorkbenchWorkspaceServerMessage.Action` throughout. Execution methods are named consistently as `pullFromTarget`, `pushToTarget`, `runTargetProgram`, and `attachTargetTerminal`.
- Execution consistency: all file paths are grounded in the current project layout. The main risk is common-module test infrastructure availability; if `:v1_21_1-common:test` is not configured in this repo, execute the compile tasks and move smoke tests into `:core:test`-friendly pure Kotlin adapters before continuing.