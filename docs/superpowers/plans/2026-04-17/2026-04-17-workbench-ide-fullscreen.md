# Workbench IDE Fullscreen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the workbench screen into a fullscreen IDE with a hideable bottom terminal dock, a visible target computer slot, and restored lifecycle controls.

**Architecture:** Replace the editor-versus-terminal page split with a single fullscreen workbench shell. Introduce explicit fullscreen layout regions, promote terminal visibility to a dock sub-state, and wire the target slot and lifecycle actions through real menu/runtime pathways instead of placeholder UI.

**Tech Stack:** Kotlin, Minecraft/NeoForge menu + screen APIs, existing workbench store/state model, Gradle test tasks.

---

## File Map

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`
  - Replace page-level terminal/editor switching with fullscreen IDE sub-state.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
  - Drive terminal dock visibility and updated action state.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
  - Compute fullscreen header/sidebar/editor/status/dock/slot bounds.
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`
  - Lock fullscreen and dock bounds with pure layout tests.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
  - Support updated target-slot/menu synchronization.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt`
  - Replace inactive placeholder slot behavior with one visible target slot surface.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt`
  - Carry enough target state for the fullscreen header/slot surface.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
  - Back the target slot with persistent server-side target item/descriptor state.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
  - Expose target/reboot runtime operations cleanly.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
  - Add missing lifecycle action message(s) if needed by the new header controls.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
  - Remove the no-op reboot path and wire lifecycle controls to the server.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
  - Render the fullscreen IDE shell, slot surface, header controls, and bottom docked terminal.
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`
  - Cover dock visibility and lifecycle action dispatch.
- Modify: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`
  - Cover visible active target slot behavior.

### Task 1: Convert Workbench State To Fullscreen IDE + Dock Model

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Write the failing store tests**

```kotlin
@Test
fun terminalDockStartsHiddenAndCanBeToggled() =
    runTest(UnconfinedTestDispatcher()) {
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())

        assertFalse(store.state.terminalVisible)

        store.toggleTerminalVisibility()
        assertTrue(store.state.terminalVisible)

        store.toggleTerminalVisibility()
        assertFalse(store.state.terminalVisible)
    }

@Test
fun rebootDelegatesToControlGateway() =
    runTest(UnconfinedTestDispatcher()) {
        val controlGateway = FakeComputerControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())

        store.rebootComputer()

        assertEquals(1, controlGateway.rebootCalls)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: FAIL because `terminalVisible`, `toggleTerminalVisibility()`, and reboot assertions are not implemented yet.

- [ ] **Step 3: Implement the minimal fullscreen state changes**

```kotlin
data class WorkbenchState(
    val terminalVisible: Boolean = false,
    // keep existing editor/target/sync fields
)

fun toggleTerminalVisibility() {
    _state.value = state.copy(terminalVisible = !state.terminalVisible)
}

fun toggleMode() {
    toggleTerminalVisibility()
}
```

- [ ] **Step 4: Update the fake control gateway used by tests**

```kotlin
private class FakeComputerControlGateway : ComputerControlGateway {
    var rebootCalls: Int = 0

    override fun reboot() {
        rebootCalls += 1
    }
}
```

- [ ] **Step 5: Run the tests again**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: PASS.

### Task 2: Add Fullscreen Layout Bounds And Bottom Dock Geometry

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`

- [ ] **Step 1: Write the failing layout tests**

```kotlin
@Test
fun fullscreenLayoutAllocatesHeaderSidebarEditorAndStatusBar() {
    val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

    assertEquals(UiRect(0, 0, 1280, 32), layout.headerBounds)
    assertTrue(layout.sidebarBounds.width > 0)
    assertTrue(layout.editorBounds.height > 0)
    assertEquals(20, layout.statusBarBounds.height)
}

@Test
fun visibleTerminalDockReducesEditorHeight() {
    val hidden = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)
    val shown = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, true, fontMetrics)

    assertTrue(shown.editorBounds.height < hidden.editorBounds.height)
    assertTrue(shown.terminalDockBounds!!.height > 0)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: FAIL because the fullscreen API and dock bounds do not exist yet.

- [ ] **Step 3: Implement fullscreen layout regions**

```kotlin
data class WorkbenchLayoutModel(
    val headerBounds: UiRect,
    val sidebarBounds: UiRect,
    val editorBounds: UiRect,
    val statusBarBounds: UiRect,
    val terminalDockBounds: UiRect?,
    val targetSlotBounds: UiRect,
    val terminalToggleBounds: UiRect,
    val rebootBounds: UiRect,
)

companion object {
    fun fullscreen(
        leftPos: Int,
        topPos: Int,
        screenWidth: Int,
        screenHeight: Int,
        terminalVisible: Boolean,
        font: FontMetrics,
    ): WorkbenchLayoutModel { /* compute explicit regions */ }
}
```

- [ ] **Step 4: Keep existing editor helpers bound to the new regions**

```kotlin
fun visibleEditorLines(): Int = ((editorBounds.height - 6) / LINE_HEIGHT).coerceAtLeast(1)

fun editorTextOrigin(): Pair<Int, Int> = editorBounds.x + 40 to editorBounds.y + 6
```

- [ ] **Step 5: Run the layout tests again**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: PASS.

### Task 3: Back The Target Slot With Real Workbench Data

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/data/WorkbenchContainerData.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuWithoutInventory.kt`
- Test: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/WorkbenchMenuSmokeTest.kt`

- [ ] **Step 1: Write the failing menu smoke test for an active target slot**

```kotlin
@Test
fun exposesActiveTargetSlotForWorkbenchHeader() {
    TestMinecraftBootstrap.ensureInitialized()

    val menu = WorkbenchMenuWithoutInventory(
        MenuType.GENERIC_9x1,
        5,
        TestInventoryFactory.create(),
        WorkbenchContainerData(),
    )

    assertTrue(menu.slots.first().isActive)
}
```

- [ ] **Step 2: Run the smoke test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: FAIL because all workbench slots are currently inactive placeholders.

- [ ] **Step 3: Replace placeholder slot behavior with one dedicated target slot contract**

```kotlin
class WorkbenchMenuWithoutInventory(...) : AbstractWorkbenchMenu(...) {
    init {
        addSlot(
            object : Slot(targetContainer, 0, 0, 0) {
                override fun mayPlace(stack: ItemStack): Boolean = ServerWorkbench.extractTargetDescriptor(stack).computerId != null
                override fun isActive(): Boolean = true
            },
        )
    }
}

// `targetContainer` here is a dedicated single-slot Container backed by the workbench block entity target stack.
```

- [ ] **Step 4: Persist and surface the backing target item/descriptor**

```kotlin
class WorkbenchBlockEntity(...) {
    private var targetStack: ItemStack = ItemStack.EMPTY

    fun setTargetStack(stack: ItemStack) {
        targetStack = stack.copy().also { it.count = 1 }
        setTarget(targetStack)
        setChanged()
    }
}

class WorkbenchContainerData(..., val targetPresent: Boolean)
```

- [ ] **Step 5: Run the smoke test again**

Run: `./gradlew :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: PASS.

### Task 4: Restore Lifecycle Actions Through The Workbench Runtime Path

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/menu/AbstractWorkbenchMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Write the failing control-path test at the store boundary**

```kotlin
@Test
fun runAndRebootStayAvailableWhenTargetIsConnected() =
    runTest(UnconfinedTestDispatcher()) {
        val controlGateway = FakeComputerControlGateway()
        val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(WorkbenchRemoteState(target = WorkbenchTargetState(connected = true)))

        assertTrue(store.state.actions.canRun)
        store.rebootComputer()
        assertEquals(1, controlGateway.rebootCalls)
    }
```

- [ ] **Step 2: Run the store test to verify the reboot path still fails higher up**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: FAIL until the reboot path is wired completely.

- [ ] **Step 3: Add an explicit workbench reboot action to the common networking path**

```kotlin
enum class Action {
    LIST,
    READ,
    WRITE,
    PULL,
    PUSH,
    RUN,
    REBOOT,
    ATTACH_TERMINAL,
}

override fun reboot() {
    ClientNetworking.sendToServer(WorkbenchWorkspaceServerMessage(menu, WorkbenchWorkspaceServerMessage.Action.REBOOT))
}
```

- [ ] **Step 4: Teach `ServerWorkbench` and `AbstractWorkbenchMenu` how to execute reboot**

```kotlin
fun rebootTarget() {
    if (targetDescriptor.computerId == null) return
    runtimeBridge.rebootTarget(targetDescriptor)
}

when (action) {
    WorkbenchWorkspaceServerMessage.Action.REBOOT -> {
        workbench.rebootTarget()
        workbench.snapshot(_workspaceStateFlow.value.document?.path)
    }
}
```

- [ ] **Step 5: Run the focused tests again**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --console=plain`

Expected: PASS.

### Task 5: Rebuild `WorkbenchEditorScreen` As A Fullscreen IDE Shell

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModelTest.kt`

- [ ] **Step 1: Write the failing fullscreen layout assertions needed by the screen**

```kotlin
@Test
fun fullscreenLayoutExposesTargetSlotAndDockToggleArea() {
    val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

    assertTrue(layout.targetSlotBounds.width > 0)
    assertTrue(layout.headerBounds.height > 0)
}
```

- [ ] **Step 2: Run the layout test to verify it fails first**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --console=plain`

Expected: FAIL until the fullscreen header/slot API is complete.

- [ ] **Step 3: Replace page-based rendering in the screen with fullscreen shell rendering**

```kotlin
override fun init() {
    imageWidth = width
    imageHeight = height
    leftPos = 0
    topPos = 0
    super.init()
}

override fun renderBg(...) {
    renderFullscreenChrome(graphics)
    renderHeader(graphics)
    renderSidebar(graphics)
    renderEditor(graphics)
    if (store.state.terminalVisible) {
        renderDockedTerminal(graphics, mouseX, mouseY)
    }
}
```

- [ ] **Step 4: Convert the old IDE/Console button into a terminal visibility toggle and render the target slot/header controls**

```kotlin
private fun handleHeaderClick(mouseX: Int, mouseY: Int): Boolean {
    if (layout().terminalToggleBounds.contains(mouseX, mouseY)) {
        store.toggleTerminalVisibility()
        return true
    }
    if (layout().rebootBounds.contains(mouseX, mouseY)) {
        store.rebootComputer()
        return true
    }
    return false
}
```

- [ ] **Step 5: Run compile and focused tests**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

### Task 6: Final Verification

**Files:**
- Modify: none

- [ ] **Step 1: Run the full focused verification set**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStoreTest' --tests 'ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModelTest' :v1_21_1-common:test --tests 'ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest' --console=plain`

Expected: PASS.

- [ ] **Step 2: Run compile verification for the affected module graph**

Run: `./gradlew :core:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 3: Perform manual in-game checks**

```text
1. Open the workbench.
2. Confirm the IDE fills the screen.
3. Confirm one target computer slot is visible in the header.
4. Insert or inspect the target computer and confirm its metadata updates.
5. Show and hide the bottom terminal dock.
6. Confirm the editor expands when the dock is hidden.
7. Trigger Run and Reboot from the header controls.
8. Confirm terminal focus still works when the dock is visible.
```

- [ ] **Step 4: Record any manual-only gaps before execution handoff**

```text
If any manual check cannot be completed in the current environment, document that explicitly in the final execution summary.
```