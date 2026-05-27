# Screen-First Unified UI DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working slice of the new screen-first unified UI DSL by introducing a minimal interactive UI foundation and rewriting `ComputerTerminalScreen` on top of it.

**Architecture:** Keep the new authoring surface centered on a `DslScreen`/`DslContainerScreen` host and a small runtime that owns layout, drawing, hit testing, focus, and input routing together. Do not build a compiler pipeline, renderer/backend abstraction, or binding-slot API. Keep legacy `UiNode`/`UiRenderer` code alive temporarily for `WorkbenchEditorScreen`, but treat it as a migration fallback rather than the target architecture.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, `:core` unit tests, `:v1_21_1-common` compilation and smoke tests, Minecraft `GuiGraphics`, existing terminal metrics and input-controller helpers.

---

## Scope Check

This plan intentionally implements one complete vertical slice:

- a new core UI runtime for layout + draw + interaction
- a Minecraft screen host for that runtime
- a first interactive primitive set that can express buttons and terminal surfaces
- a direct rewrite of `ComputerTerminalScreen`

This plan does **not** rewrite `WorkbenchEditorScreen`. That remains on the legacy path until the terminal slice proves the API shape.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt` | Create | Typed dynamic value surface for scalar and structural expressions |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Create | Modifier chain for layout, appearance, click, focus, key input, and visibility |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt` | Create | Element model for `box`, `column`, `text`, `button`, `terminalSurface`, and `custom` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiFrame.kt` | Create | Flattened frame model with draw commands, bounds, and interaction regions |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntime.kt` | Create | Layout pass, frame building, hit testing, focus ownership, and event dispatch |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt` | Create | Runtime tests for clickable buttons, focusable terminal surfaces, and conditional nodes |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt` | Delete | Legacy render-only test replaced by runtime-level tests |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/DslContainerScreen.kt` | Create | Minecraft screen host that owns one UI runtime instance |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/GuiGraphicsFrameRenderer.kt` | Create | Minecraft renderer for `UiFrame.DrawCommand` values |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/TerminalSurfaceBridge.kt` | Create | Bridge from terminal primitive callbacks to `WorkbenchTerminalInputController` and `FixedWidthFontRenderer` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Modify | Replace manual render/input orchestration with the new DSL host |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt` | Keep | Legacy fallback for workbench until a later rewrite |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt` | Modify | Re-anchor terminal bounds expectations around the new button/terminal composition if needed |
| `docs/ARCHITECTURE.md` | Modify | Document the new screen-first foundation and note legacy coexistence |

### Task 1: Introduce the core interactive UI runtime

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiFrame.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntime.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt`
- Delete: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`

- [ ] **Step 1: Write the failing runtime tests first**

Create `UiRuntimeTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiRuntimeTest {
    @Test
    fun clickDispatchActivatesTopmostButton() {
        val events = mutableListOf<String>()
        val runtime = UiRuntime(rootWidth = 200, rootHeight = 120)

        runtime.setContent {
            ui {
                button(text = textExpr { "Behind" }, modifier = Modifier.offset(8, 8)) {
                    onClick { events += "behind" }
                }
                button(text = textExpr { "Front" }, modifier = Modifier.offset(8, 8).zIndex(1)) {
                    onClick { events += "front" }
                }
            }
        }

        runtime.rebuildFrame()
        assertTrue(runtime.mouseClicked(12, 12))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun terminalSurfaceRequestsFocusAndReceivesKeyboard() {
        val events = mutableListOf<String>()
        val runtime = UiRuntime(rootWidth = 240, rootHeight = 140)

        runtime.setContent {
            ui {
                terminalSurface(
                    snapshot = expr { "snapshot" },
                    modifier = Modifier.offset(16, 16).size(96, 48).focusable(),
                    onFocus = { events += "focus" },
                    onKey = { keyCode -> events += "key:$keyCode"; true },
                )
            }
        }

        runtime.rebuildFrame()
        assertTrue(runtime.mouseClicked(20, 20))
        assertTrue(runtime.keyPressed(257))
        assertEquals(listOf("focus", "key:257"), events)
    }

    @Test
    fun ifNodeRemovesHiddenButtonFromHitTesting() {
        var visible = false
        val runtime = UiRuntime(rootWidth = 120, rootHeight = 80)

        runtime.setContent {
            ui {
                if_(expr { visible }) {
                    button(text = textExpr { "Shown" }) { }
                }
            }
        }

        runtime.rebuildFrame()
        assertFalse(runtime.mouseClicked(4, 4))

        visible = true
        runtime.rebuildFrame()
        assertTrue(runtime.mouseClicked(4, 4))
    }
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --console=plain`

Expected: FAIL because the `core.ui.foundation` package and runtime types do not exist yet.

- [ ] **Step 3: Add the core expression, modifier, element, and frame model**

Create `UiExpression.kt`, `UiModifier.kt`, `UiElement.kt`, and `UiFrame.kt` with this minimum surface:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.foundation

fun interface UiExpression<T> {
    fun evaluate(): T
}

fun <T> expr(block: () -> T): UiExpression<T> = UiExpression(block)
fun textExpr(block: () -> String): UiExpression<String> = expr(block)

data class UiModifier(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val zIndex: Int = 0,
    val focusable: Boolean = false,
) {
    fun offset(x: Int, y: Int): UiModifier = copy(x = this.x + x, y = this.y + y)
    fun size(width: Int, height: Int): UiModifier = copy(width = width, height = height)
    fun zIndex(value: Int): UiModifier = copy(zIndex = value)
    fun focusable(): UiModifier = copy(focusable = true)

    companion object {
        val Empty = UiModifier()
    }
}

val Modifier: UiModifier = UiModifier.Empty

sealed interface UiElement {
    val modifier: UiModifier

    data class Box(
        override val modifier: UiModifier = Modifier,
        val children: List<UiElement>,
    ) : UiElement

    data class Button(
        override val modifier: UiModifier = Modifier,
        val text: UiExpression<String>,
        val onClick: () -> Unit,
    ) : UiElement

    data class TerminalSurface(
        override val modifier: UiModifier = Modifier,
        val snapshot: UiExpression<Any?>,
        val onFocus: () -> Unit,
        val onKey: (Int) -> Boolean,
    ) : UiElement

    data class IfNode(
        override val modifier: UiModifier = Modifier,
        val condition: UiExpression<Boolean>,
        val children: List<UiElement>,
    ) : UiElement
}

data class UiBounds(val x: Int, val y: Int, val width: Int, val height: Int)

data class UiFrame(
    val drawCommands: List<DrawCommand>,
    val interactionRegions: List<InteractionRegion>,
) {
    sealed interface DrawCommand {
        data class Button(val bounds: UiBounds, val label: String, val focused: Boolean) : DrawCommand
        data class Terminal(val bounds: UiBounds, val snapshot: Any?, val focused: Boolean) : DrawCommand
    }

    data class InteractionRegion(
        val id: String,
        val bounds: UiBounds,
        val zIndex: Int,
        val focusable: Boolean,
        val onClick: (() -> Unit)? = null,
        val onFocus: (() -> Unit)? = null,
        val onKey: ((Int) -> Boolean)? = null,
    )
}
```

- [ ] **Step 4: Implement the minimal runtime and remove the legacy render-only test**

Create `UiRuntime.kt` and delete `TerminalUiBuilderTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.foundation

class UiRuntime(
    private val rootWidth: Int,
    private val rootHeight: Int,
) {
    private var content: (() -> UiElement?) = { null }
    private var frame: UiFrame = UiFrame(emptyList(), emptyList())
    private var focusedId: String? = null

    fun setContent(content: () -> UiElement?) {
        this.content = content
    }

    fun rebuildFrame() {
        val drawCommands = mutableListOf<UiFrame.DrawCommand>()
        val regions = mutableListOf<UiFrame.InteractionRegion>()
        content()?.let { flatten(it, 0, 0, drawCommands, regions, "root") }
        frame = UiFrame(drawCommands, regions.sortedByDescending { it.zIndex })
    }

    fun currentFrame(): UiFrame = frame

    fun mouseClicked(x: Int, y: Int): Boolean {
        val hit = frame.interactionRegions.firstOrNull { region ->
            x >= region.bounds.x && y >= region.bounds.y &&
                x < region.bounds.x + region.bounds.width &&
                y < region.bounds.y + region.bounds.height
        } ?: return false

        if (hit.focusable) {
            focusedId = hit.id
            hit.onFocus?.invoke()
        }
        hit.onClick?.invoke()
        return true
    }

    fun keyPressed(keyCode: Int): Boolean {
        val focused = frame.interactionRegions.firstOrNull { it.id == focusedId } ?: return false
        return focused.onKey?.invoke(keyCode) ?: false
    }

    private fun flatten(
        element: UiElement,
        parentX: Int,
        parentY: Int,
        drawCommands: MutableList<UiFrame.DrawCommand>,
        regions: MutableList<UiFrame.InteractionRegion>,
        idPrefix: String,
    ) {
        when (element) {
            is UiElement.Box -> {
                element.children.forEachIndexed { index, child ->
                    flatten(child, parentX + element.modifier.x, parentY + element.modifier.y, drawCommands, regions, "$idPrefix-box-$index")
                }
            }

            is UiElement.Button -> {
                val bounds = UiBounds(parentX + element.modifier.x, parentY + element.modifier.y, element.modifier.width ?: 88, element.modifier.height ?: 20)
                val id = "$idPrefix-button"
                drawCommands += UiFrame.DrawCommand.Button(bounds, element.text.evaluate(), focusedId == id)
                regions += UiFrame.InteractionRegion(id, bounds, element.modifier.zIndex, element.modifier.focusable, onClick = element.onClick)
            }

            is UiElement.TerminalSurface -> {
                val bounds = UiBounds(parentX + element.modifier.x, parentY + element.modifier.y, element.modifier.width ?: rootWidth, element.modifier.height ?: rootHeight)
                val id = "$idPrefix-terminal"
                drawCommands += UiFrame.DrawCommand.Terminal(bounds, element.snapshot.evaluate(), focusedId == id)
                regions += UiFrame.InteractionRegion(id, bounds, element.modifier.zIndex, element.modifier.focusable, onFocus = element.onFocus, onKey = element.onKey)
            }

            is UiElement.IfNode -> {
                if (element.condition.evaluate()) {
                    element.children.forEachIndexed { index, child ->
                        flatten(child, parentX + element.modifier.x, parentY + element.modifier.y, drawCommands, regions, "$idPrefix-if-$index")
                    }
                }
            }
        }
    }
}

class UiScope {
    private val children = mutableListOf<UiElement>()

    fun box(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Box(modifier, UiScope().apply(block).build())
    }

    fun button(text: UiExpression<String>, modifier: UiModifier = Modifier, block: ButtonScope.() -> Unit) {
        val scope = ButtonScope().apply(block)
        children += UiElement.Button(modifier, text, scope.onClick)
    }

    fun terminalSurface(
        snapshot: UiExpression<Any?>,
        modifier: UiModifier = Modifier,
        onFocus: () -> Unit = {},
        onKey: (Int) -> Boolean = { false },
    ) {
        children += UiElement.TerminalSurface(modifier, snapshot, onFocus, onKey)
    }

    fun if_(condition: UiExpression<Boolean>, block: UiScope.() -> Unit) {
        children += UiElement.IfNode(Modifier, condition, UiScope().apply(block).build())
    }

    fun build(): List<UiElement> = children
}

class ButtonScope {
    var onClick: () -> Unit = {}
    fun onClick(block: () -> Unit) {
        onClick = block
    }
}

fun ui(block: UiScope.() -> Unit): UiElement = UiElement.Box(children = UiScope().apply(block).build())
```

Delete: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`

- [ ] **Step 5: Run the core runtime tests and commit the foundation skeleton**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --console=plain`

Expected: PASS.

Commit:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt
git commit -m "feat: add screen-first ui runtime foundation"
```

### Task 2: Add the Minecraft screen host and frame renderer

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/DslContainerScreen.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/GuiGraphicsFrameRenderer.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/TerminalSurfaceBridge.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`

- [ ] **Step 1: Add a regression frame-shape test that matches the terminal screen slice**

Add this test to `UiRuntimeTest.kt`:

```kotlin
@Test
fun terminalScreenFrameContainsButtonsAndTerminalSurface() {
    val runtime = UiRuntime(rootWidth = 220, rootHeight = 140)

    runtime.setContent {
        ui {
            button(text = textExpr { "Power" }, modifier = Modifier.offset(8, 8).size(44, 20)) { }
            button(text = textExpr { "Reboot" }, modifier = Modifier.offset(56, 8).size(52, 20)) { }
            terminalSurface(
                snapshot = expr { "snapshot" },
                modifier = Modifier.offset(8, 34).size(200, 90).focusable(),
            )
        }
    }

    runtime.rebuildFrame()

    assertEquals(3, runtime.currentFrame().drawCommands.size)
    assertEquals(3, runtime.currentFrame().interactionRegions.size)
}
```

- [ ] **Step 2: Run the test to lock the frame shape before adding the Minecraft host**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest.terminalScreenFrameContainsButtonsAndTerminalSurface" --console=plain`

Expected: PASS.

- [ ] **Step 3: Add the Minecraft host classes**

Create `DslContainerScreen.kt`, `GuiGraphicsFrameRenderer.kt`, and `TerminalSurfaceBridge.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.ui.foundation

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntime

abstract class DslContainerScreen<T : AbstractContainerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<T>(menu, inventory, title) {
    protected abstract fun buildContent(runtime: UiRuntime)

    protected open fun renderDsl(graphics: GuiGraphics, font: Font, runtime: UiRuntime) {
        GuiGraphicsFrameRenderer(graphics, font).render(runtime.currentFrame())
    }
}
```

```kotlin
package ru.lazyhat.compukterkraft.common.ui.foundation

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.common.ui.render.FixedWidthFontRenderer
import ru.lazyhat.compukterkraft.core.ui.foundation.UiFrame

class GuiGraphicsFrameRenderer(
    private val graphics: GuiGraphics,
    private val font: Font,
) {
    fun render(frame: UiFrame) {
        frame.drawCommands.forEach { command ->
            when (command) {
                is UiFrame.DrawCommand.Button -> {
                    graphics.fill(command.bounds.x, command.bounds.y, command.bounds.x + command.bounds.width, command.bounds.y + command.bounds.height, 0xFF1D2330.toInt())
                    graphics.drawString(font, command.label, command.bounds.x + 6, command.bounds.y + 6, 0xFFE6ECF5.toInt(), false)
                }

                is UiFrame.DrawCommand.Terminal -> {
                    graphics.fill(command.bounds.x - 1, command.bounds.y - 1, command.bounds.x + command.bounds.width + 1, command.bounds.y + command.bounds.height + 1, 0xFF222938.toInt())
                    TerminalSurfaceBridge.draw(graphics, command.bounds.x, command.bounds.y, command.snapshot)
                }
            }
        }
    }
}
```

```kotlin
package ru.lazyhat.compukterkraft.common.ui.foundation

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import ru.lazyhat.compukterkraft.common.ui.render.FixedWidthFontRenderer
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

object TerminalSurfaceBridge {
    fun draw(graphics: GuiGraphics, x: Int, y: Int, snapshot: Any?) {
        val typedSnapshot = snapshot as? ScreenBufferSnapshot ?: return
        val renderType = RenderType.text(FixedWidthFontRenderer.FONT)
        val buffers = MultiBufferSource.immediate(ByteBufferBuilder(renderType.bufferSize()))
        val emitter = FixedWidthFontRenderer.toVertexConsumer(graphics.pose(), buffers.getBuffer(renderType))
        FixedWidthFontRenderer.drawTerminal(emitter, x.toFloat(), y.toFloat(), typedSnapshot, 0f, 0f, 0f, 0f)
        buffers.endBatch()
    }
}
```

- [ ] **Step 4: Extend the runtime test and compile the Minecraft host slice**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS for the core tests and successful Kotlin compilation for the new host classes.

- [ ] **Step 5: Commit the screen host bridge**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt
git commit -m "feat: add minecraft host for screen-first ui runtime"
```

### Task 3: Rewrite `ComputerTerminalScreen` on the new foundation

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`
- Test: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`

- [ ] **Step 1: Add a regression contract test for the terminal screen composition**

Add this test to `UiRuntimeTest.kt`:

```kotlin
@Test
fun computerTerminalScreenContractUsesTwoButtonsAndOneFocusableTerminal() {
    val runtime = UiRuntime(rootWidth = 260, rootHeight = 180)
    var focused = false

    runtime.setContent {
        ui {
            button(text = textExpr { "Power" }, modifier = Modifier.offset(8, 8).size(44, 20)) { }
            button(text = textExpr { "Reboot" }, modifier = Modifier.offset(56, 8).size(52, 20)) { }
            terminalSurface(
                snapshot = expr { "snapshot" },
                modifier = Modifier.offset(8, 34).size(244, 130).focusable(),
                onFocus = { focused = true },
            )
        }
    }

    runtime.rebuildFrame()
    assertTrue(runtime.mouseClicked(12, 40))
    assertTrue(focused)
}
```

- [ ] **Step 2: Run the test and verify that the composition contract is enforced before screen wiring**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest.computerTerminalScreenContractUsesTwoButtonsAndOneFocusableTerminal" --console=plain`

Expected: PASS in `:core` before touching Minecraft screen code.

- [ ] **Step 3: Replace the manual terminal rendering path in `ComputerTerminalScreen.kt`**

Rewrite `ComputerTerminalScreen.kt` so it extends the new host and builds one DSL tree:

```kotlin
class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : DslContainerScreen<T>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val runtime = UiRuntime(rootWidth = 0, rootHeight = 0)

    override fun containerTick() {
        super.containerTick()
        terminalInput.update()
        buildContent(runtime)
        runtime.rebuildFrame()
    }

    override fun buildContent(runtime: UiRuntime) {
        val snapshot = menu.clientSide.screenSnapshot
        val layout = terminalLayout()
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, snapshot)

        runtime.setContent {
            ui {
                button(text = textExpr { "Power" }, modifier = Modifier.offset(leftPos + 8, topPos + 8).size(44, 20)) {
                    onClick { inputHandler.accept(ControlInputEvent(ComputerControlAction.TURN_ON)) }
                }
                button(text = textExpr { "Reboot" }, modifier = Modifier.offset(leftPos + 56, topPos + 8).size(52, 20)) {
                    onClick { inputHandler.accept(ControlInputEvent(ComputerControlAction.REBOOT)) }
                }
                if_(expr { terminalState is WorkbenchTerminalViewState.Active }) {
                    terminalSurface(
                        snapshot = expr { (terminalState as WorkbenchTerminalViewState.Active).snapshot },
                        modifier = Modifier.offset(layout.terminalBounds.x, layout.terminalBounds.y).size(layout.terminalBounds.width, layout.terminalBounds.height).focusable(),
                        onFocus = { terminalInput.focused = true },
                        onKey = { key -> terminalInput.keyPressed(key, 0, 0) },
                    )
                }
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && runtime.mouseClicked(mouseX.toInt(), mouseY.toInt())) {
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun keyPressed(key: Int, scancode: Int, modifiers: Int): Boolean {
        return runtime.keyPressed(key) || super.keyPressed(key, scancode, modifiers)
    }
}
```

- [ ] **Step 4: Compile and run the regression-safe test set**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit the first rewritten screen**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt
git commit -m "feat: rewrite computer terminal screen with unified ui dsl"
```

### Task 4: Document the new architecture and legacy coexistence

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Update the architecture document after code lands**

Add this section to `docs/ARCHITECTURE.md`:

```md
### Screen-First UI Foundation

The primary UI authoring surface is now a screen-hosted DSL rather than a render-only builder pipeline.

- `core.ui.foundation` owns the Minecraft-agnostic UI runtime, frame model, layout, and interaction routing.
- `common.ui.foundation` owns Minecraft screen hosting and `GuiGraphics` rendering.
- `ComputerTerminalScreen` is the first screen migrated to the new foundation.
- Legacy `core.ui.dsl` and `common.ui.render.WorkbenchTerminalRenderer` remain temporarily for workbench migration only.
```

- [ ] **Step 2: Verify docs and compile together**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 3: Commit the architecture note**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: record screen-first ui foundation architecture"
```

## Self-Review Checklist

- The plan never introduces a renderer/backend abstraction as an author-facing concept.
- The plan keeps the first slice focused on `ComputerTerminalScreen` and does not silently expand into a full workbench rewrite.
- Every test command exists in `:core` or `:v1_21_1-common` and is consistent with the files touched in the task.
- Legacy workbench paths remain buildable while the new screen-first slice is introduced.
- The old April 18 compiled-DSL design is treated as superseded rather than mixed into the new implementation.