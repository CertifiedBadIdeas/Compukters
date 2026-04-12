# Terminal Workbench UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the decorative `Terminal` band from workbench terminal mode, make the terminal workspace occupy the full available terminal area, and show a powered-off placeholder without a misleading focus hint.

**Architecture:** Keep `ComputerWorkbenchScreen` as the input/lifecycle coordinator, but move terminal presentation state into the pure terminal UI builder. Extend the core terminal layout model so the UI can render a full terminal surface area while still drawing the fixed-size terminal character grid within it. Use a small pure policy helper for focus-hint and input-availability rules so the powered-on/off behavior is testable without Minecraft.

**Tech Stack:** Kotlin, Gradle, kotlin.test, pure core UI DSL in `modules/core`, common Minecraft screen/render glue in `modules/v1_21_1/v1_21_1-common`.

---

### Task 1: Expand the Core Terminal Layout Model

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalLayout.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.lazyhat.compukterkraft.core.gui

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchTerminalMetricsTest {
    @Test
    fun layout_exposes_full_terminal_surface_above_status_bar() {
        val layout = WorkbenchTerminalMetrics.layout(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            terminalColumns = 16,
            terminalRows = 8,
        )

        assertEquals(TerminalRect(8, 34, 464, 218), layout.terminalSurfaceBounds)
        assertEquals(TerminalRect(8, 34, 96, 72), layout.terminalBounds)
        assertEquals(TerminalRect(8, 252, 464, 20), layout.statusBounds)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest"`
Expected: FAIL because `terminalSurfaceBounds` does not exist and `terminalBounds` is still centered instead of anchored to the full terminal surface.

- [ ] **Step 3: Write minimal implementation**

```kotlin
data class WorkbenchTerminalLayout(
    val panelBounds: TerminalRect,
    val terminalSurfaceBounds: TerminalRect,
    val terminalBounds: TerminalRect,
    val statusBounds: TerminalRect,
)

object WorkbenchTerminalMetrics {
    private const val MIN_IMAGE_WIDTH = 480
    private const val MIN_IMAGE_HEIGHT = 280
    private const val OUTER_PADDING = 8
    private const val CONTENT_TOP = 34
    private const val STATUS_HEIGHT = 20

    fun layout(
        leftPos: Int,
        topPos: Int,
        imageWidth: Int,
        imageHeight: Int,
        terminalColumns: Int,
        terminalRows: Int,
    ): WorkbenchTerminalLayout {
        val panelBounds =
            TerminalRect(
                leftPos + OUTER_PADDING,
                topPos + CONTENT_TOP,
                imageWidth - OUTER_PADDING * 2,
                imageHeight - CONTENT_TOP - OUTER_PADDING,
            )
        val statusBounds =
            TerminalRect(
                panelBounds.x,
                panelBounds.y + panelBounds.height - STATUS_HEIGHT,
                panelBounds.width,
                STATUS_HEIGHT,
            )
        val terminalSurfaceBounds =
            TerminalRect(
                panelBounds.x,
                panelBounds.y,
                panelBounds.width,
                statusBounds.y - panelBounds.y,
            )

        val terminalBounds =
            TerminalRect(
                terminalSurfaceBounds.x,
                terminalSurfaceBounds.y,
                terminalColumns * TerminalFontConstants.FONT_WIDTH,
                terminalRows * TerminalFontConstants.FONT_HEIGHT,
            )

        return WorkbenchTerminalLayout(
            panelBounds = panelBounds,
            terminalSurfaceBounds = terminalSurfaceBounds,
            terminalBounds = terminalBounds,
            statusBounds = statusBounds,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalLayout.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt
git commit -m "refactor: expose full terminal surface layout"
```

### Task 2: Add Pure Terminal UI States for Active and Powered-Off Modes

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package ru.lazyhat.compukterkraft.core.ui.dsl

import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalUiBuilderTest {
    private val snapshot = ScreenBufferSnapshot.empty(width = 16, height = 8, colour = true)
    private val layout = WorkbenchTerminalMetrics.layout(0, 0, 480, 280, 16, 8)

    @Test
    fun powered_off_view_shows_placeholder_without_terminal_title_or_focus_hint() {
        val nodes = buildTerminalUi(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            layout = layout,
            snapshot = snapshot,
            focused = false,
            poweredOn = false,
            showFocusHint = false,
            placeholderText = "Computer is off. Turn it on first.",
        )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Computer is off. Turn it on first." in texts)
        assertFalse("Terminal" in texts)
        assertFalse("Click terminal to focus input" in texts)
        assertTrue(nodes.none { it is TerminalView })
    }

    @Test
    fun active_view_shows_focus_hint_only_when_terminal_is_unfocused() {
        val nodes = buildTerminalUi(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            layout = layout,
            snapshot = snapshot,
            focused = false,
            poweredOn = true,
            showFocusHint = true,
            placeholderText = "Computer is off. Turn it on first.",
        )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Click terminal to focus input" in texts)
        assertFalse("Computer is off. Turn it on first." in texts)
        assertTrue(nodes.any { it is TerminalView })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest"`
Expected: FAIL because `buildTerminalUi` does not accept `poweredOn` / `placeholderText`, still renders the `Terminal` title, always emits `TerminalView`, and always derives status text only from `focused`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
fun buildTerminalUi(
    leftPos: Int,
    topPos: Int,
    imageWidth: Int,
    imageHeight: Int,
    layout: WorkbenchTerminalLayout,
    snapshot: ScreenBufferSnapshot,
    focused: Boolean,
    poweredOn: Boolean,
    showFocusHint: Boolean,
    placeholderText: String,
): List<UiNode> =
    buildList {
        add(Rect(leftPos, topPos, imageWidth, imageHeight, TerminalColors.WINDOW_BACKGROUND))
        add(Rect(layout.panelBounds.x, layout.panelBounds.y, layout.panelBounds.width, layout.panelBounds.height, TerminalColors.PANEL_BACKGROUND))
        add(Rect(layout.panelBounds.x, layout.panelBounds.y, layout.panelBounds.width, 1, TerminalColors.PANEL_BORDER))
        add(Rect(layout.statusBounds.x, layout.statusBounds.y, layout.statusBounds.width, layout.statusBounds.height, TerminalColors.STATUS_BACKGROUND))

        val borderColour = if (focused) TerminalColors.TERMINAL_BORDER_FOCUSED else TerminalColors.TERMINAL_BORDER
        add(Rect(layout.terminalSurfaceBounds.x - 1, layout.terminalSurfaceBounds.y - 1, layout.terminalSurfaceBounds.width + 2, layout.terminalSurfaceBounds.height + 2, borderColour))
        add(Rect(layout.terminalSurfaceBounds.x, layout.terminalSurfaceBounds.y, layout.terminalSurfaceBounds.width, layout.terminalSurfaceBounds.height, TerminalColors.TERMINAL_BACKGROUND))

        val statusText = if (showFocusHint) "Click terminal to focus input" else "Input active  |  Ctrl+V paste"
        if (poweredOn) {
            add(Text(layout.statusBounds.x + 12, layout.statusBounds.y + 6, statusText, TerminalColors.MUTED_TEXT))
            add(TerminalView(layout.terminalBounds.x, layout.terminalBounds.y, snapshot))
        } else {
            add(Text(layout.terminalSurfaceBounds.x + 12, layout.terminalSurfaceBounds.y + 12, placeholderText, TerminalColors.MUTED_TEXT))
        }

        add(RightAlignedText(layout.statusBounds.x + 12, layout.statusBounds.y + 6, layout.statusBounds.width - 24, "${snapshot.width} x ${snapshot.height}", TerminalColors.MUTED_TEXT))
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt
git commit -m "feat: add powered-off terminal ui state"
```

### Task 3: Make Focus and Input Availability Explicit and Testable

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicy.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchTerminalInteractionPolicyTest {
    @Test
    fun hides_focus_hint_when_powered_off() {
        assertFalse(WorkbenchTerminalInteractionPolicy.showFocusHint(poweredOn = false, focused = false))
        assertTrue(WorkbenchTerminalInteractionPolicy.showFocusHint(poweredOn = true, focused = false))
    }

    @Test
    fun blocks_terminal_input_when_powered_off_or_not_in_terminal_mode() {
        assertFalse(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, poweredOn = false, focused = true))
        assertFalse(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.EDITOR, poweredOn = true, focused = true))
        assertTrue(WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, poweredOn = true, focused = true))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest"`
Expected: FAIL because `WorkbenchTerminalInteractionPolicy` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchMode

object WorkbenchTerminalInteractionPolicy {
    fun showFocusHint(
        poweredOn: Boolean,
        focused: Boolean,
    ): Boolean = poweredOn && !focused

    fun canAcceptInput(
        mode: WorkbenchMode,
        poweredOn: Boolean,
        focused: Boolean,
    ): Boolean = mode == WorkbenchMode.TERMINAL && poweredOn && focused
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicy.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicyTest.kt
git commit -m "refactor: extract terminal interaction policy"
```

### Task 4: Wire Common Screen, Renderer, and Localization to the New Model

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-fabric/src/main/resources/assets/compukterkraft/lang/en_us.json`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchTerminalInteractionPolicyTest.kt`

- [ ] **Step 1: Write the failing integration check**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest" :v1_21_1-common:compileKotlin`
Expected: FAIL because the common renderer still calls the old `buildTerminalUi(...)` signature and the screen still hardcodes powered-off terminal rendering behavior outside the UI model.

- [ ] **Step 2: Update the renderer to pass presentation state into the core builder**

```kotlin
object WorkbenchTerminalRenderer {
    fun render(
        graphics: GuiGraphics,
        font: Font,
        leftPos: Int,
        topPos: Int,
        imageWidth: Int,
        imageHeight: Int,
        layout: WorkbenchTerminalLayout,
        snapshot: ScreenBufferSnapshot,
        focused: Boolean,
        poweredOn: Boolean,
        showFocusHint: Boolean,
        placeholderText: String,
    ) {
        val nodes = buildTerminalUi(
            leftPos = leftPos,
            topPos = topPos,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            layout = layout,
            snapshot = snapshot,
            focused = focused,
            poweredOn = poweredOn,
            showFocusHint = showFocusHint,
            placeholderText = placeholderText,
        )
        UiRenderer.render(graphics, font, nodes)
    }
}
```

- [ ] **Step 3: Update the workbench screen to use the interaction policy and renderer state**

```kotlin
override fun containerTick() {
    super.containerTick()
    if (!menu.isComputerOn && terminalInput.focused) {
        terminalInput.focused = false
    }
    terminalInput.update()
}

override fun renderBg(graphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
    if (store.state.mode == WorkbenchMode.TERMINAL) {
        val snapshot = menu.clientSide.screenSnapshot
        val focused = WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, menu.isComputerOn, terminalInput.focused)
        val showFocusHint = WorkbenchTerminalInteractionPolicy.showFocusHint(menu.isComputerOn, terminalInput.focused)
        WorkbenchTerminalRenderer.render(
            graphics = graphics,
            font = minecraft!!.font,
            leftPos = leftPos,
            topPos = topPos,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            layout = terminalLayout(),
            snapshot = snapshot,
            focused = focused,
            poweredOn = menu.isComputerOn,
            showFocusHint = showFocusHint,
            placeholderText = Component.translatable("gui.compukterkraft.terminal.powered_off").string,
        )
    } else {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF12151D.toInt())
        graphics.fill(leftPos + 8, topPos + 34, leftPos + 128, topPos + imageHeight - 12, 0xFF1D2330.toInt())
        graphics.fill(leftPos + 136, topPos + 34, leftPos + imageWidth - 8, topPos + imageHeight - 32, 0xFF0D1016.toInt())
        graphics.fill(leftPos + 136, topPos + imageHeight - 28, leftPos + imageWidth - 8, topPos + imageHeight - 8, 0xFF161B25.toInt())
    }
    renderToolbar(graphics)
}

override fun keyPressed(key: Int, scancode: Int, modifiers: Int): Boolean {
    if (WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, menu.isComputerOn, terminalInput.focused)) {
        if (terminalInput.keyPressed(key, scancode, modifiers)) {
            return true
        }
    }
    return super.keyPressed(key, scancode, modifiers)
}
```

- [ ] **Step 4: Add localization for the powered-off placeholder**

```json
"gui.compukterkraft.terminal.powered_off": "Computer is off. Turn it on first."
```

Add that key to both:

```text
modules/v1_21_1/v1_21_1-fabric/src/main/resources/assets/compukterkraft/lang/en_us.json
modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json
```

- [ ] **Step 5: Run verification**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" --tests "ru.lazyhat.compukterkraft.core.ui.dsl.TerminalUiBuilderTest" --tests "ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicyTest" :v1_21_1-common:compileKotlin :v1_21_1-fabric:processResources :v1_21_1-neoforge:processResources`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt modules/v1_21_1/v1_21_1-fabric/src/main/resources/assets/compukterkraft/lang/en_us.json modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json
git commit -m "feat: add powered-off workbench terminal placeholder"
```

## Verification Checklist

- The `Terminal` title no longer appears in terminal mode.
- The rendered terminal surface fills the panel area above the status bar.
- The powered-off placeholder renders in the terminal workspace instead of the old inactive terminal look.
- The focus hint is hidden while powered off.
- Terminal input remains blocked while powered off and unchanged while powered on.

## Manual QA

1. Open a computer workbench in terminal mode while the computer is on.
2. Confirm the `Terminal` title is gone and the black terminal surface reaches the workbench panel edges above the status bar.
3. Click outside the terminal, then check that the focus hint appears only in the powered-on state.
4. Turn the computer off and confirm the placeholder text appears.
5. Verify that clicking or typing while powered off does not activate terminal input.
6. Turn the computer back on and confirm the terminal resumes normal interaction.