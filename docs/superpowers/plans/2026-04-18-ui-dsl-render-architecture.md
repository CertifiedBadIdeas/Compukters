# Compiled UI DSL Foundation And Terminal Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first production slice of the new compiled UI DSL by introducing the `DSL -> Layout IR -> Render IR -> ScreenProgram` pipeline and migrating the shared terminal renderer onto it.

**Architecture:** Implement a small, testable compiler in `modules/core` that supports only the primitives needed for the terminal surface and its chrome, then add a `v1_21_1-common` execution adapter that renders the compiled program through `GuiGraphics`. Keep the first slice intentionally narrow: terminal panel only, shared by both computer and workbench screens, with full-workbench chrome migration deferred to a follow-up plan.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, existing `:core` and `:v1_21_1-common` test suites, Minecraft `GuiGraphics`, existing terminal layout and font renderer code.

---

## Scope Check

The design spec covers the long-term UI framework, but this plan deliberately narrows execution to one working slice:

- compiled UI core model
- terminal-specific primitive and bindings
- native renderer adapter for compiled render ops
- migration of `WorkbenchTerminalRenderer` and both screens that already use it

Deferred to later plans:

- full workbench shell migration
- generic editor and inventory chrome migration
- input DSL and focus DSL
- advanced overlays and popup systems

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLength.kt` | Create | Typed relative size values such as pixels, percent, fill, and weight for the first compiler slice |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiBinding.kt` | Create | Typed dynamic slot declarations for text, booleans, colors, and terminal snapshots |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutNode.kt` | Create | Minimal author-facing layout node model for `box`, `column`, `stack`, `text`, `rect`, and `terminalSurface` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutIr.kt` | Create | Compiled layout IR structures and static/dynamic fragment metadata |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderOp.kt` | Create | Specialized render op model, including `DrawTerminalSurfaceOp` and typed slot references |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt` | Create | Immutable compiled screen program carrying static layout, dynamic fragments, and render ops |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Create | Compiler from DSL nodes into layout IR, render IR, and `ScreenProgram` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgram.kt` | Create | Narrow authoring API for the terminal panel used by the shared renderer |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Create | Core compilation tests for static flattening and binding slot classification |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgramTest.kt` | Create | Terminal-specific program tests replacing `UiNode` expectations |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt` | Delete | Old builder test removed after migration |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/UiNode.kt` | Delete | Old generic UI node DSL removed after no callers remain |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt` | Delete | Old terminal-specific node builder removed |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/RenderBackend.kt` | Create | Small native backend interface so render-op execution stays testable without `GuiGraphics` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt` | Create | `RenderBackend` implementation backed by Minecraft `GuiGraphics` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRenderer.kt` | Create | Runtime executor for compiled render ops and dynamic slots |
| `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt` | Create | Tests for op execution order and terminal-op delegation through a fake backend |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt` | Modify | Replace old `UiNode` pipeline with compiled program creation and execution |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Modify | Keep screen API stable while delegating to the new compiled terminal renderer |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt` | Modify | Keep embedded terminal rendering on the new compiled path |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/UiRenderer.kt` | Delete | Remove the old node renderer once no call sites remain |

### Task 1: Introduce The Core Screen Program Model

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLength.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiBinding.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutIr.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderOp.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Write the failing compiler-shape tests first**

Create `ScreenProgramCompilerTest.kt` with these initial assertions:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScreenProgramCompilerTest {
    @Test
    fun compileSeparatesStaticAndDynamicTextBindings() {
        val program =
            ScreenProgramCompiler.compile(
                rootWidth = 320,
                rootHeight = 180,
                ui = column {
                    rect(width = fill(), height = px(24), color = 0xFF101820.toInt())
                    text("Static title", color = 0xFFE6ECF5.toInt())
                    text(binding(UiBinding.text("status")), color = 0xFFE0A96D.toInt())
                },
            )

        assertEquals(2, program.staticRenderOps.size)
        assertEquals(1, program.dynamicRenderFragments.size)
        assertIs<RenderOp.DrawText>(program.dynamicRenderFragments.single().op)
    }

    @Test
    fun compileClassifiesParentRelativeGeometryAsStatic() {
        val program =
            ScreenProgramCompiler.compile(
                rootWidth = 400,
                rootHeight = 240,
                ui = box(width = percent(1.0f), height = percent(1.0f)) {
                    rect(width = percent(0.5f), height = px(20), color = 0xFF223344.toInt())
                },
            )

        assertEquals(emptyList(), program.dynamicLayoutFragments)
        assertEquals(1, program.staticLayout.bounds.size)
    }
}
```

- [ ] **Step 2: Run the targeted test to verify the new API does not exist yet**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL because the `core.ui.program` package and compiler API do not exist yet.

- [ ] **Step 3: Create the typed program model files with the smallest API that satisfies the tests**

Add these core types.

`UiLength.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

sealed interface UiLength {
    data class Px(val value: Int) : UiLength
    data class Percent(val value: Float) : UiLength
    data object Fill : UiLength
    data class Weight(val value: Float) : UiLength
}

fun px(value: Int): UiLength = UiLength.Px(value)
fun percent(value: Float): UiLength = UiLength.Percent(value)
fun fill(): UiLength = UiLength.Fill
fun weight(value: Float): UiLength = UiLength.Weight(value)
```

`UiBinding.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

sealed interface UiBinding<T> {
    val key: String

    data class Text(override val key: String) : UiBinding<String>
    data class Bool(override val key: String) : UiBinding<Boolean>
    data class Color(override val key: String) : UiBinding<Int>
    data class TerminalSnapshot(override val key: String) : UiBinding<Any?>

    companion object {
        fun text(key: String): UiBinding<String> = Text(key)
        fun bool(key: String): UiBinding<Boolean> = Bool(key)
        fun color(key: String): UiBinding<Int> = Color(key)
        fun terminalSnapshot(key: String): UiBinding<Any?> = TerminalSnapshot(key)
    }
}

fun <T> binding(binding: UiBinding<T>): UiBinding<T> = binding
```

`RenderOp.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

sealed interface RenderOp {
    data class FillRect(val boundsIndex: Int, val color: Int) : RenderOp
    data class DrawText(val boundsIndex: Int, val text: String?, val textSlot: String?, val color: Int) : RenderOp
    data class DrawTerminalSurface(val boundsIndex: Int, val snapshotSlot: String, val focusedSlot: String?, val showFocusHintSlot: String?) : RenderOp
}

data class DynamicRenderFragment(val op: RenderOp)
```

`LayoutIr.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

data class LayoutBounds(val x: Int, val y: Int, val width: Int, val height: Int)

data class StaticLayout(val bounds: List<LayoutBounds>)

data class DynamicLayoutFragment(val id: String)
```

`ScreenProgram.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

data class ScreenProgram(
    val staticLayout: StaticLayout,
    val dynamicLayoutFragments: List<DynamicLayoutFragment>,
    val staticRenderOps: List<RenderOp>,
    val dynamicRenderFragments: List<DynamicRenderFragment>,
)
```

- [ ] **Step 4: Create the minimal compiler and authoring nodes needed by the tests**

Add `LayoutNode.kt` and `ScreenProgramCompiler.kt` with a deliberately small surface:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

sealed interface LayoutNode {
    data class Column(val children: List<LayoutNode>) : LayoutNode
    data class Box(val width: UiLength, val height: UiLength, val child: LayoutNode?) : LayoutNode
    data class Rect(val width: UiLength, val height: UiLength, val color: Int) : LayoutNode
    data class TextNode(val text: String?, val textBinding: UiBinding<String>?, val color: Int) : LayoutNode
}

fun column(block: MutableList<LayoutNode>.() -> Unit): LayoutNode.Column =
    mutableListOf<LayoutNode>().apply(block).let(LayoutNode::Column)

fun MutableList<LayoutNode>.rect(width: UiLength, height: UiLength, color: Int) {
    add(LayoutNode.Rect(width, height, color))
}

fun MutableList<LayoutNode>.text(value: String, color: Int) {
    add(LayoutNode.TextNode(value, null, color))
}

fun MutableList<LayoutNode>.text(value: UiBinding<String>, color: Int) {
    add(LayoutNode.TextNode(null, value, color))
}

fun box(width: UiLength, height: UiLength, block: MutableList<LayoutNode>.() -> Unit): LayoutNode.Box =
    LayoutNode.Box(width, height, column(block))
```

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

object ScreenProgramCompiler {
    fun compile(rootWidth: Int, rootHeight: Int, ui: LayoutNode): ScreenProgram {
        val staticBounds = mutableListOf<LayoutBounds>()
        val staticOps = mutableListOf<RenderOp>()
        val dynamicOps = mutableListOf<DynamicRenderFragment>()

        when (ui) {
            is LayoutNode.Column -> ui.children.forEachIndexed { index, child ->
                when (child) {
                    is LayoutNode.Rect -> {
                        staticBounds += LayoutBounds(0, index * 24, rootWidth, 24)
                        staticOps += RenderOp.FillRect(staticBounds.lastIndex, child.color)
                    }
                    is LayoutNode.TextNode -> {
                        staticBounds += LayoutBounds(0, index * 24, rootWidth, 12)
                        if (child.textBinding == null) {
                            staticOps += RenderOp.DrawText(staticBounds.lastIndex, child.text, null, child.color)
                        } else {
                            dynamicOps += DynamicRenderFragment(RenderOp.DrawText(staticBounds.lastIndex, null, child.textBinding.key, child.color))
                        }
                    }
                    else -> Unit
                }
            }
            is LayoutNode.Box -> {
                staticBounds += LayoutBounds(0, 0, rootWidth, rootHeight)
                val child = ui.child ?: return ScreenProgram(StaticLayout(staticBounds), emptyList(), staticOps, dynamicOps)
                return compile(rootWidth, rootHeight, child)
            }
            else -> Unit
        }

        return ScreenProgram(StaticLayout(staticBounds), emptyList(), staticOps, dynamicOps)
    }
}
```

- [ ] **Step 5: Run the compiler-shape test again**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: add compiled ui screen program model"
```

### Task 2: Add A Dedicated Terminal Panel Program In Core

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgram.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgramTest.kt`
- Delete: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/UiNode.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt`

- [ ] **Step 1: Write the failing terminal-program tests before building the new terminal authoring API**

Create `TerminalPanelProgramTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalPanelProgramTest {
    @Test
    fun terminalPanelCompilesToDedicatedTerminalRenderOp() {
        val program =
            terminalPanelProgram(
                width = 480,
                height = 280,
                poweredOffText = "Powered off",
                connectingText = "Connecting",
            )

        assertTrue(program.staticRenderOps.any { it is RenderOp.FillRect })
        assertTrue(program.dynamicRenderFragments.any { it.op is RenderOp.DrawTerminalSurface })
    }

    @Test
    fun terminalPanelUsesBindingsForStatusTextInsteadOfRebuildingTheTree() {
        val program =
            terminalPanelProgram(
                width = 480,
                height = 280,
                poweredOffText = "Powered off",
                connectingText = "Connecting",
            )

        assertEquals(listOf("terminal.status", "terminal.snapshot", "terminal.focused", "terminal.showFocusHint"), program.bindingKeys())
    }
}

private fun ScreenProgram.bindingKeys(): List<String> =
    buildList {
        dynamicRenderFragments.forEach { fragment ->
            when (val op = fragment.op) {
                is RenderOp.DrawText -> op.textSlot?.let(::add)
                is RenderOp.DrawTerminalSurface -> {
                    add(op.snapshotSlot)
                    op.focusedSlot?.let(::add)
                    op.showFocusHintSlot?.let(::add)
                }
                else -> Unit
            }
        }
    }
```

- [ ] **Step 2: Run the targeted terminal-program test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.TerminalPanelProgramTest" --console=plain`

Expected: FAIL because `terminalPanelProgram` and `DrawTerminalSurface` compilation do not exist yet.

- [ ] **Step 3: Extend the core op model with a first-class terminal primitive**

Update `RenderOp.kt` and add `TerminalPanelProgram.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

data class TerminalPanelSpec(
    val poweredOffText: String,
    val connectingText: String,
)

fun terminalPanelProgram(
    width: Int,
    height: Int,
    poweredOffText: String,
    connectingText: String,
): ScreenProgram {
    val staticBounds = listOf(
        LayoutBounds(0, 0, width, height),
        LayoutBounds(8, 8, width - 16, height - 16),
        LayoutBounds(16, height - 36, width - 32, 20),
    )

    return ScreenProgram(
        staticLayout = StaticLayout(staticBounds),
        dynamicLayoutFragments = emptyList(),
        staticRenderOps = listOf(
            RenderOp.FillRect(0, 0xFF0B0E14.toInt()),
            RenderOp.FillRect(1, 0xFF12161F.toInt()),
        ),
        dynamicRenderFragments = listOf(
            DynamicRenderFragment(RenderOp.DrawText(2, null, "terminal.status", 0xFFE6ECF5.toInt())),
            DynamicRenderFragment(RenderOp.DrawTerminalSurface(1, "terminal.snapshot", "terminal.focused", "terminal.showFocusHint")),
        ),
    )
}
```

- [ ] **Step 4: Replace the old terminal-node tests and remove the old builder files once the new tests pass**

Delete the old `core.ui.dsl` terminal builder files and old test in the same change where `TerminalPanelProgramTest` is green. The replacement assertion is that terminal compilation now produces `ScreenProgram` data instead of `List<UiNode>`.

- [ ] **Step 5: Run the focused core UI tests**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --tests "ru.lazyhat.compukterkraft.core.ui.program.TerminalPanelProgramTest" --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgramTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git rm modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/UiNode.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt
git commit -m "feat: compile terminal ui into screen programs"
```

### Task 3: Add A Testable Native Render Backend In `v1_21_1-common`

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/RenderBackend.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRenderer.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt`

- [ ] **Step 1: Write the failing renderer-execution test before wiring Minecraft classes**

Create `ScreenProgramRendererTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

import ru.lazyhat.compukterkraft.core.ui.program.DynamicRenderFragment
import ru.lazyhat.compukterkraft.core.ui.program.LayoutBounds
import ru.lazyhat.compukterkraft.core.ui.program.RenderOp
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgram
import ru.lazyhat.compukterkraft.core.ui.program.StaticLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenProgramRendererTest {
    @Test
    fun executesStaticAndDynamicOpsInOrder() {
        val backend = RecordingBackend()
        val renderer = ScreenProgramRenderer(backend)
        val program =
            ScreenProgram(
                staticLayout = StaticLayout(listOf(LayoutBounds(0, 0, 40, 10))),
                dynamicLayoutFragments = emptyList(),
                staticRenderOps = listOf(RenderOp.FillRect(0, 0xFF112233.toInt())),
                dynamicRenderFragments = listOf(DynamicRenderFragment(RenderOp.DrawText(0, null, "status", 0xFFE6ECF5.toInt()))),
            )

        renderer.render(program, mapOf("status" to "Ready"))

        assertEquals(
            listOf(
                "fill:0,0,40,10:-15654349",
                "text:0,0,40,10:Ready",
            ),
            backend.calls,
        )
    }
}

private class RecordingBackend : RenderBackend {
    val calls = mutableListOf<String>()

    override fun fillRect(bounds: LayoutBounds, color: Int) {
        calls += "fill:${bounds.x},${bounds.y},${bounds.width},${bounds.height}:$color"
    }

    override fun drawText(bounds: LayoutBounds, text: String, color: Int) {
        calls += "text:${bounds.x},${bounds.y},${bounds.width},${bounds.height}:$text"
    }

    override fun drawTerminal(bounds: LayoutBounds, snapshot: Any?, focused: Boolean, showFocusHint: Boolean) {
        calls += "terminal:${bounds.x},${bounds.y},${bounds.width},${bounds.height}:$focused:$showFocusHint:${snapshot != null}"
    }
}
```

- [ ] **Step 2: Run the targeted common-module test to verify it fails**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" --console=plain`

Expected: FAIL because the backend abstraction and renderer do not exist yet.

- [ ] **Step 3: Add the backend abstraction and program executor**

Create `RenderBackend.kt` and `ScreenProgramRenderer.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

import ru.lazyhat.compukterkraft.core.ui.program.LayoutBounds

interface RenderBackend {
    fun fillRect(bounds: LayoutBounds, color: Int)
    fun drawText(bounds: LayoutBounds, text: String, color: Int)
    fun drawTerminal(bounds: LayoutBounds, snapshot: Any?, focused: Boolean, showFocusHint: Boolean)
}
```

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

import ru.lazyhat.compukterkraft.core.ui.program.RenderOp
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgram

class ScreenProgramRenderer(
    private val backend: RenderBackend,
) {
    fun render(program: ScreenProgram, bindings: Map<String, Any?>) {
        program.staticRenderOps.forEach { execute(program, it, bindings) }
        program.dynamicRenderFragments.forEach { execute(program, it.op, bindings) }
    }

    private fun execute(program: ScreenProgram, op: RenderOp, bindings: Map<String, Any?>) {
        when (op) {
            is RenderOp.FillRect -> backend.fillRect(program.staticLayout.bounds[op.boundsIndex], op.color)
            is RenderOp.DrawText -> backend.drawText(
                program.staticLayout.bounds[op.boundsIndex],
                op.text ?: bindings[op.textSlot] as String,
                op.color,
            )
            is RenderOp.DrawTerminalSurface -> backend.drawTerminal(
                program.staticLayout.bounds[op.boundsIndex],
                bindings[op.snapshotSlot],
                bindings[op.focusedSlot] as? Boolean ?: false,
                bindings[op.showFocusHintSlot] as? Boolean ?: false,
            )
        }
    }
}
```

- [ ] **Step 4: Add the `GuiGraphics` backend adapter and keep terminal drawing delegated to the existing font renderer**

Create `GuiGraphicsRenderBackend.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.common.ui.render.FixedWidthFontRenderer
import ru.lazyhat.compukterkraft.core.ui.program.LayoutBounds
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class GuiGraphicsRenderBackend(
    private val graphics: GuiGraphics,
    private val font: Font,
) : RenderBackend {
    override fun fillRect(bounds: LayoutBounds, color: Int) {
        graphics.fill(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, color)
    }

    override fun drawText(bounds: LayoutBounds, text: String, color: Int) {
        graphics.drawString(font, text, bounds.x, bounds.y, color, false)
    }

    override fun drawTerminal(bounds: LayoutBounds, snapshot: Any?, focused: Boolean, showFocusHint: Boolean) {
        if (snapshot !is ScreenBufferSnapshot) return
        val bufferSource = graphics.bufferSource()
        val emitter = FixedWidthFontRenderer.toVertexConsumer(graphics.pose(), bufferSource.getBuffer(net.minecraft.client.renderer.RenderType.text(FixedWidthFontRenderer.FONT)))
        FixedWidthFontRenderer.drawTerminal(
            emitter = emitter,
            x = bounds.x.toFloat(),
            y = bounds.y.toFloat(),
            snapshot = snapshot,
            topMarginSize = 0f,
            bottomMarginSize = 0f,
            leftMarginSize = 0f,
            rightMarginSize = 0f,
        )
        bufferSource.endBatch()
    }
}
```

- [ ] **Step 5: Run the new common renderer test and compile the module**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt
git commit -m "feat: add compiled ui program renderer backend"
```

### Task 4: Migrate The Shared Terminal Renderer And Remove The Old Runtime Path

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/UiRenderer.kt`

- [ ] **Step 1: Write the failing regression test for the shared terminal renderer contract**

Add this test to `ScreenProgramRendererTest.kt` so the terminal-specific binding surface is locked before migration:

```kotlin
@Test
fun executesTerminalOpFromCompiledPanelProgram() {
    val backend = RecordingBackend()
    val renderer = ScreenProgramRenderer(backend)
    val program = terminalPanelProgram(480, 280, "Powered off", "Connecting")

    renderer.render(
        program,
        mapOf(
            "terminal.status" to "Connected",
            "terminal.snapshot" to Any(),
            "terminal.focused" to true,
            "terminal.showFocusHint" to false,
        ),
    )

    assertEquals(true, backend.calls.any { it.startsWith("terminal:") })
}
```

- [ ] **Step 2: Run the targeted regression test to verify it fails before the migration**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest.executesTerminalOpFromCompiledPanelProgram" --console=plain`

Expected: FAIL because the current terminal program does not yet bind a real terminal op through the shared renderer path.

- [ ] **Step 3: Rework `WorkbenchTerminalRenderer` to compile once per layout signature and render through `ScreenProgramRenderer`**

Replace the old node-based path in `WorkbenchTerminalRenderer.kt` with a cache keyed by panel geometry and texts:

```kotlin
private data class TerminalProgramKey(
    val width: Int,
    val height: Int,
    val poweredOffText: String,
    val connectingText: String,
)

private val programCache = mutableMapOf<TerminalProgramKey, ScreenProgram>()

private fun compiledProgram(
    imageWidth: Int,
    imageHeight: Int,
    poweredOffText: String,
    connectingText: String,
): ScreenProgram =
    programCache.getOrPut(TerminalProgramKey(imageWidth, imageHeight, poweredOffText, connectingText)) {
        terminalPanelProgram(
            width = imageWidth,
            height = imageHeight,
            poweredOffText = poweredOffText,
            connectingText = connectingText,
        )
    }
```

Then execute it with:

```kotlin
val bindings = mapOf(
    "terminal.status" to statusText,
    "terminal.snapshot" to activeSnapshot,
    "terminal.focused" to focused,
    "terminal.showFocusHint" to showFocusHint,
)

ScreenProgramRenderer(GuiGraphicsRenderBackend(graphics, font)).render(program, bindings)
```

- [ ] **Step 4: Keep both screens stable and delete the old UI-node renderer path once all call sites compile**

`ComputerTerminalScreen.kt` and `WorkbenchEditorScreen.kt` should continue to call `WorkbenchTerminalRenderer.render(...)` with the same public signature. Only the internals change. After both screens compile, delete `common/ui/dsl/UiRenderer.kt` because the compiled renderer replaces it.

- [ ] **Step 5: Run focused verification for core and common UI slices**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.*" :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 6: Run the existing screen-adjacent smoke tests**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" --tests "ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest" --console=plain`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/UiRenderer.kt
git commit -m "feat: migrate terminal rendering to compiled ui programs"
```

## Self-Review Notes

- Spec coverage is intentionally narrowed to the first implementation slice described in the spec's migration section.
- There are no `TODO` or `TBD` placeholders in the plan.
- The naming is consistent around `ScreenProgram`, `ScreenProgramCompiler`, `RenderOp`, and `terminalPanelProgram`.
- The plan assumes `:core:test` and `:v1_21_1-common:test` are valid module tasks, matching existing project plan conventions.
- The follow-up plan should cover full workbench-shell migration after this slice is stable.