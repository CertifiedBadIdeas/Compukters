# Terminal Connecting State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fake terminal fallback snapshot with an explicit `connecting` UI state that is shown only while a powered-on computer is waiting for its first real screen snapshot.

**Architecture:** Introduce a dedicated terminal presentation state, make client terminal snapshots nullable until the first sync arrives, and render `PoweredOff`, `Connecting`, and `Active` through the shared terminal UI DSL. Keep input and focus disabled outside `Active`, and recompute terminal sizing only when a real snapshot exists.

**Tech Stack:** Kotlin, Gradle, shared core UI DSL, Minecraft common GUI/menu code, kotlin.test

---

## File Structure

- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalViewState.kt`
  - Own the explicit terminal presentation model and pure mapping function from `isComputerOn + snapshot?`.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt`
  - Render `PoweredOff`, `Connecting`, and `Active` without assuming a snapshot always exists.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicy.kt`
  - Keep focus and input rules aligned with the new view state.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt`
  - Accept the explicit view state and pass it into the DSL builder.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/menu/AbstractComputerMenu.kt`
  - Store nullable latest snapshot on the client instead of inventing a fallback.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/data/ComputerContainerData.kt`
  - Encode terminal snapshot presence explicitly instead of forcing a default snapshot.
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt`
  - Build the new view state, use placeholder sizing while waiting, and switch to grid sizing when a real snapshot arrives.
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalViewStateTest.kt`
  - Verify pure state mapping.
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`
  - Verify `Connecting` rendering.
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicyTest.kt`
  - Verify focus/input stays disabled outside `Active`.

### Task 1: Add Explicit Terminal View State

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalViewState.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalViewStateTest.kt`

- [ ] **Step 1: Write the failing state-mapping test**

```kotlin
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertIs

class WorkbenchTerminalViewStateTest {
    @Test
    fun derivesPoweredOffConnectingAndActiveStates() {
        val snapshot = ScreenBufferSnapshot.empty(width = 10, height = 5, colour = true)

        assertIs<WorkbenchTerminalViewState.PoweredOff>(
            WorkbenchTerminalViewState.from(isComputerOn = false, snapshot = null),
        )
        assertIs<WorkbenchTerminalViewState.Connecting>(
            WorkbenchTerminalViewState.from(isComputerOn = true, snapshot = null),
        )
        assertIs<WorkbenchTerminalViewState.Active>(
            WorkbenchTerminalViewState.from(isComputerOn = true, snapshot = snapshot),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewStateTest"`
Expected: FAIL with unresolved `WorkbenchTerminalViewState`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

sealed interface WorkbenchTerminalViewState {
    data object PoweredOff : WorkbenchTerminalViewState
    data object Connecting : WorkbenchTerminalViewState
    data class Active(val snapshot: ScreenBufferSnapshot) : WorkbenchTerminalViewState

    companion object {
        fun from(
            isComputerOn: Boolean,
            snapshot: ScreenBufferSnapshot?,
        ): WorkbenchTerminalViewState =
            when {
                !isComputerOn -> PoweredOff
                snapshot == null -> Connecting
                else -> Active(snapshot)
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewStateTest"`
Expected: PASS

### Task 2: Render Connecting In The Shared UI DSL

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicy.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicyTest.kt`

- [ ] **Step 1: Extend the failing UI test for connecting**

```kotlin
@Test
fun connectingViewShowsOnlyPlaceholderText() {
    val nodes = buildTerminalUi(
        leftPos = 0,
        topPos = 0,
        imageWidth = 480,
        imageHeight = 280,
        layout = layout,
        terminalState = WorkbenchTerminalViewState.Connecting,
        focused = false,
        showFocusHint = false,
        poweredOffText = "Computer is off. Turn it on first.",
        connectingText = "Connecting...",
    )

    val texts = nodes.filterIsInstance<Text>().map { it.text }

    assertTrue("Connecting..." in texts)
    assertFalse("Click terminal to focus input" in texts)
    assertFalse(texts.any { it.contains(" x ") })
    assertTrue(nodes.none { it is TerminalView })
}
```

- [ ] **Step 2: Add the failing interaction-policy test**

```kotlin
@Test
fun connectingStateCannotAcceptInputOrShowHint() {
    assertFalse(
        WorkbenchTerminalInteractionPolicy.showFocusHint(
            terminalState = WorkbenchTerminalViewState.Connecting,
            focused = false,
        ),
    )
    assertFalse(
        WorkbenchTerminalInteractionPolicy.canAcceptInput(
            mode = WorkbenchMode.TERMINAL,
            terminalState = WorkbenchTerminalViewState.Connecting,
            focused = true,
        ),
    )
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest"`
Expected: FAIL because builder and policy still rely on `poweredOn + snapshot`

- [ ] **Step 4: Write minimal implementation**

```kotlin
fun buildTerminalUi(
    leftPos: Int,
    topPos: Int,
    imageWidth: Int,
    imageHeight: Int,
    layout: WorkbenchTerminalLayout,
    terminalState: WorkbenchTerminalViewState,
    focused: Boolean,
    showFocusHint: Boolean,
    poweredOffText: String,
    connectingText: String,
): List<UiNode>
```

```kotlin
object WorkbenchTerminalInteractionPolicy {
    fun showFocusHint(
        terminalState: WorkbenchTerminalViewState,
        focused: Boolean,
    ): Boolean = terminalState is WorkbenchTerminalViewState.Active && !focused

    fun canAcceptInput(
        mode: WorkbenchMode,
        terminalState: WorkbenchTerminalViewState,
        focused: Boolean,
    ): Boolean =
        mode == WorkbenchMode.TERMINAL &&
            terminalState is WorkbenchTerminalViewState.Active &&
            focused
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest"`
Expected: PASS

### Task 3: Remove Fallback Snapshot From Menu And Container Flow

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/menu/AbstractComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/data/ComputerContainerData.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/ComputerTerminalDefaults.kt`

- [ ] **Step 1: Remove the unused fallback helper reference in the client flow**

```kotlin
class Client(
    initialSnapshot: ScreenBufferSnapshot?,
) : MenuSide {
    private val _screenSnapshot = MutableStateFlow(initialSnapshot)

    val screenSnapshotFlow: StateFlow<ScreenBufferSnapshot?> = _screenSnapshot.asStateFlow()
    val screenSnapshot: ScreenBufferSnapshot? get() = _screenSnapshot.value
}
```

- [ ] **Step 2: Encode snapshot presence explicitly in container data**

```kotlin
class ComputerContainerData private constructor(
    val family: ComputerFamily,
    val terminalSnapshot: ScreenBufferSnapshot?,
    val displayStack: ItemStack,
    val uploadMaxSize: Int,
)
```

```kotlin
override fun toBytes(buffer: RegistryFriendlyByteBuf) {
    buffer.writeEnum(family)
    buffer.writeBoolean(terminalSnapshot != null)
    terminalSnapshot?.let { TerminalState(it).write(buffer) }
    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, displayStack)
    buffer.writeInt(uploadMaxSize)
}
```

```kotlin
constructor(buffer: RegistryFriendlyByteBuf) : this(
    buffer.readEnum(ComputerFamily::class.java),
    if (buffer.readBoolean()) TerminalState(buffer).toSnapshot() else null,
    ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
    buffer.readInt(),
)
```

- [ ] **Step 3: Delete dead fallback code if it becomes unused**

```kotlin
// Remove ComputerTerminalDefaults if no production code references it after the change.
```

- [ ] **Step 4: Run compile verification**

Run: `./gradlew :v1_21_1-common:compileKotlin`
Expected: PASS after all nullable snapshot call sites are updated

### Task 4: Wire Screen, Renderer, And Layout Transition

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`

- [ ] **Step 1: Add the failing layout/sizing regression test if needed**

```kotlin
@Test
fun defaultComputerTerminalNearlyFillsWorkbenchSurface() {
    val imageWidth = WorkbenchTerminalMetrics.imageWidth(Config.DEFAULT_COMPUTER_TERM_WIDTH, Config.DEFAULT_COMPUTER_TERM_HEIGHT)
    val imageHeight = WorkbenchTerminalMetrics.imageHeight(Config.DEFAULT_COMPUTER_TERM_WIDTH, Config.DEFAULT_COMPUTER_TERM_HEIGHT)
    val layout = WorkbenchTerminalMetrics.layout(
        leftPos = 0,
        topPos = 0,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        terminalColumns = Config.DEFAULT_COMPUTER_TERM_WIDTH,
        terminalRows = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
    )

    assertTrue(layout.terminalSurfaceBounds.width - layout.terminalBounds.width < TerminalFontConstants.FONT_WIDTH)
    assertTrue(layout.terminalSurfaceBounds.height - layout.terminalBounds.height < TerminalFontConstants.FONT_HEIGHT)
}
```

- [ ] **Step 2: Update renderer and screen to use the new state**

```kotlin
val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
val showFocusHint = WorkbenchTerminalInteractionPolicy.showFocusHint(terminalState, terminalInput.focused)
```

```kotlin
WorkbenchTerminalRenderer.render(
    graphics = graphics,
    font = minecraft!!.font,
    leftPos = leftPos,
    topPos = topPos,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    layout = terminalLayout(),
    terminalState = terminalState,
    focused = terminalInput.focused,
    showFocusHint = showFocusHint,
    poweredOffText = Component.translatable("gui.compukterkraft.terminal.powered_off").string,
    connectingText = Component.translatable("gui.compukterkraft.terminal.connecting").string,
)
```

- [ ] **Step 3: Recompute placeholder sizing without a fake snapshot**

```kotlin
private fun terminalDimensionsForCurrentState(): Pair<Int, Int> =
    when (val state = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)) {
        is WorkbenchTerminalViewState.Active -> state.snapshot.width to state.snapshot.height
        else -> 0 to 0
    }
```

Use the non-active branch to keep the same placeholder-style image sizing path as the powered-off screen instead of deriving dimensions from a synthetic snapshot.

- [ ] **Step 4: Run focused verification**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewStateTest" --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest" :v1_21_1-common:compileKotlin`
Expected: PASS

## Self-Review Notes

- Spec coverage: the plan covers the three-state model, placeholder rendering, no-input rules, removal of fallback snapshots, and verification.
- Placeholder scan: no `TODO` or deferred implementation markers remain in tasks; each task includes concrete files and commands.
- Type consistency: all tasks use `WorkbenchTerminalViewState` as the shared model and keep `ScreenBufferSnapshot?` nullable only until `Active` is constructed.
- Execution consistency: the plan assumes normal branch work in the current workspace and does not require a git worktree.