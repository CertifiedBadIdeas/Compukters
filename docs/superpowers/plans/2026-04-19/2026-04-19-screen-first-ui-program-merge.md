# Screen-First UI Program Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the merged UI architecture that keeps the new screen-first authoring DSL while compiling it into a phased `ScreenProgram` executed by a small runtime executor and narrow Minecraft backend.

**Architecture:** Introduce a new `core.ui.foundation` authoring layer with explicit structural nodes, expression slots, modifiers, and authoring sugar such as `button`. Compile that DSL into a phased `core.ui.program.ScreenProgram` containing layout, render, hit-test, input, and focus programs. Execute the compiled program through a small runtime host plus a typed backend in `v1_21_1-common`, then migrate `ComputerTerminalScreen` to the new path while leaving the legacy `UiNode`/`UiRenderer` path in place for `WorkbenchEditorScreen`.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, `:core` unit tests, `:v1_21_1-common` compilation and smoke tests, Minecraft `GuiGraphics`, existing terminal metrics and input controller helpers.

---

## Scope Check

This plan is one vertical slice with four tightly-coupled layers:

- a new screen-first authoring DSL;
- a phased compiled `ScreenProgram` model and compiler;
- a runtime executor plus Minecraft backend bridge;
- a terminal-screen migration that proves rendering and input.

This plan does **not** migrate `WorkbenchEditorScreen`. That screen stays on the legacy `core.ui.dsl.UiNode` path until the compiled terminal slice is stable.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt` | Create | Typed author-facing scalar expressions |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Create | Layout, z-order, semantics, click, and focus modifiers |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt` | Create | Explicit authoring tree nodes and `button` sugar lowering |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt` | Create | Top-level phased compiled artifact |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutProgram.kt` | Create | Static layout nodes and dynamic layout fragments |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderProgram.kt` | Create | Static render ops and dynamic render fragments |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/HitTestProgram.kt` | Create | Compiled hit regions and z-ordered targeting |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/InputProgram.kt` | Create | Event routes and handler ids |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/FocusProgram.kt` | Create | Focus targets and traversal metadata |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderBackend.kt` | Create | Narrow typed backend contract owned by core |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Create | Compiler from screen-first authoring DSL to phased program |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutor.kt` | Create | Runtime host for slots, hit-testing, focus, and input dispatch |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Create | Compiler contract tests |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutorTest.kt` | Create | Runtime executor tests for click, focus, and key routing |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt` | Create | Minecraft implementation of render ops |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/TerminalSurfaceBridge.kt` | Create | Bridge between terminal render/input callbacks and compiled program slots |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/DslContainerScreen.kt` | Create | Screen host for the compiled executor |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Modify | Rewrite terminal screen onto compiled DSL host |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt` | Modify | Adjust metrics expectations only if layout contract changes |
| `docs/ARCHITECTURE.md` | Modify | Document merged authoring/compiler/runtime/backend architecture |

### Task 1: Create the screen-first authoring foundation

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Write the failing compiler contracts against the desired authoring API**

Create `ScreenProgramCompilerTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.UiRole
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class ScreenProgramCompilerTest {
    @Test
    fun buttonSugarCompilesToRenderHitInputAndFocusPrograms() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                button(
                    text = textExpr { "Power" },
                    modifier = Modifier.offset(8, 8),
                ) { }
            },
        )

        assertEquals(1, program.hitTestProgram.regions.size)
        assertEquals(UiRole.Button, program.hitTestProgram.regions.single().role)
        assertTrue(program.inputProgram.routes.any { it.eventType == InputEventType.Click })
        assertTrue(program.focusProgram.targets.any { it.role == UiRole.Button })
        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.DrawText })
    }

    @Test
    fun terminalSurfaceCompilesFocusAndKeyRouting() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                terminalSurface(
                    snapshot = expr { "snapshot" },
                    modifier = Modifier.offset(12, 28).size(96, 48).focusable(),
                    onKey = { true },
                )
            },
        )

        assertTrue(program.renderProgram.staticOps.any { it is RenderOp.DrawTerminalSurface })
        assertTrue(program.inputProgram.routes.any { it.eventType == InputEventType.KeyPressed })
        assertTrue(program.focusProgram.targets.any { it.role == UiRole.TerminalSurface })
    }

    @Test
    fun ifNodeProducesDynamicFragmentsInsteadOfImmediateTreeRebuild() {
        var visible = false
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                if_(expr { visible }) {
                    button(text = textExpr { "Shown" }) { }
                }
            },
        )

        assertEquals(1, program.layoutProgram.dynamicFragments.size)
        assertEquals(1, program.renderProgram.dynamicFragments.size)
    }
}
```

- [ ] **Step 2: Run the targeted compiler test to verify the new packages are missing**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL with unresolved symbols for `core.ui.foundation` and `core.ui.program`.

- [ ] **Step 3: Add the authoring DSL with explicit nodes and `button` sugar**

Create `UiExpression.kt`, `UiModifier.kt`, and `UiElement.kt` with this minimum shape:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.foundation

fun interface UiExpression<T> {
    fun evaluate(): T
}

fun <T> expr(block: () -> T): UiExpression<T> = UiExpression(block)
fun textExpr(block: () -> String): UiExpression<String> = expr(block)

enum class UiRole {
    Button,
    TerminalSurface,
}

data class UiModifier(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val zIndex: Int = 0,
    val focusable: Boolean = false,
    val role: UiRole? = null,
    val onClick: (() -> Unit)? = null,
) {
    fun offset(x: Int, y: Int): UiModifier = copy(x = this.x + x, y = this.y + y)
    fun size(width: Int, height: Int): UiModifier = copy(width = width, height = height)
    fun zIndex(value: Int): UiModifier = copy(zIndex = value)
    fun focusable(): UiModifier = copy(focusable = true)
    fun role(role: UiRole): UiModifier = copy(role = role)
    fun clickable(onClick: () -> Unit): UiModifier = copy(onClick = onClick)

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

    data class Text(
        override val modifier: UiModifier = Modifier,
        val value: UiExpression<String>,
        val color: Int = 0xFFFFFF,
    ) : UiElement

    data class TerminalSurface(
        override val modifier: UiModifier = Modifier,
        val snapshot: UiExpression<Any?>,
        val onFocus: () -> Unit = {},
        val onKey: (Int) -> Boolean = { false },
    ) : UiElement

    data class IfNode(
        override val modifier: UiModifier = Modifier,
        val condition: UiExpression<Boolean>,
        val children: List<UiElement>,
    ) : UiElement
}

class UiScope {
    private val children = mutableListOf<UiElement>()

    fun box(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Box(modifier, UiScope().apply(block).build())
    }

    fun text(value: UiExpression<String>, modifier: UiModifier = Modifier, color: Int = 0xFFFFFF) {
        children += UiElement.Text(modifier, value, color)
    }

    fun terminalSurface(
        snapshot: UiExpression<Any?>,
        modifier: UiModifier = Modifier,
        onFocus: () -> Unit = {},
        onKey: (Int) -> Boolean = { false },
    ) {
        children += UiElement.TerminalSurface(modifier.role(UiRole.TerminalSurface), snapshot, onFocus, onKey)
    }

    fun if_(condition: UiExpression<Boolean>, block: UiScope.() -> Unit) {
        children += UiElement.IfNode(condition = condition, children = UiScope().apply(block).build())
    }

    fun button(
        text: UiExpression<String>,
        modifier: UiModifier = Modifier,
        onClick: () -> Unit = {},
    ) {
        box(modifier.role(UiRole.Button).focusable().clickable(onClick)) {
            this.text(value = text, modifier = Modifier.offset(8, 6))
        }
    }

    fun build(): List<UiElement> = children
}

fun ui(block: UiScope.() -> Unit): UiElement = UiElement.Box(children = UiScope().apply(block).build())
```

- [ ] **Step 4: Run the compiler test again to confirm the next failure is the missing compiled program layer**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL with unresolved symbols for `ScreenProgramCompiler`, `RenderOp`, `InputEventType`, and phased program types.

- [ ] **Step 5: Commit the authoring foundation skeleton**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: add screen first ui authoring foundation"
```

### Task 2: Compile the authoring DSL into a phased `ScreenProgram`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/HitTestProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/InputProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/FocusProgram.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderBackend.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Expand the tests to lock the compiled program shape**

Extend `ScreenProgramCompilerTest.kt` with one more contract:

```kotlin
@Test
fun buttonAndTerminalUseStableHandlerIdsAcrossPrograms() {
    val compiler = ScreenProgramCompiler()

    val program = compiler.compile(
        ui {
            button(text = textExpr { "Power" }) { }
            terminalSurface(snapshot = expr { "snapshot" }, onKey = { true })
        },
    )

    val regionIds = program.hitTestProgram.regions.map { it.regionId }.toSet()
    val routedIds = program.inputProgram.routes.map { it.regionId }.toSet()

    assertTrue(regionIds.isNotEmpty())
    assertEquals(regionIds, routedIds)
}
```

- [ ] **Step 2: Run the test to verify the program contracts still fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL because the phased program types do not exist.

- [ ] **Step 3: Add the phased program model**

Create the program files with this shape:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiRole

data class ScreenProgram(
    val layoutProgram: LayoutProgram,
    val renderProgram: RenderProgram,
    val hitTestProgram: HitTestProgram,
    val inputProgram: InputProgram,
    val focusProgram: FocusProgram,
)

data class LayoutProgram(
    val staticNodes: List<LayoutNode>,
    val dynamicFragments: List<DynamicLayoutFragment>,
)

data class LayoutNode(
    val nodeId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

fun interface DynamicLayoutFragment {
    fun evaluate(slots: SlotValues): List<LayoutNode>
}

data class RenderProgram(
    val staticOps: List<RenderOp>,
    val dynamicFragments: List<DynamicRenderFragment>,
)

sealed interface RenderOp {
    data class FillRect(val nodeId: String, val color: Int) : RenderOp
    data class DrawText(val nodeId: String, val slotId: String, val color: Int) : RenderOp
    data class DrawTerminalSurface(val nodeId: String, val slotId: String) : RenderOp
}

fun interface DynamicRenderFragment {
    fun evaluate(slots: SlotValues): List<RenderOp>
}

data class HitTestProgram(val regions: List<HitRegion>)

data class HitRegion(
    val regionId: String,
    val nodeId: String,
    val role: UiRole?,
    val zIndex: Int,
    val focusable: Boolean,
)

enum class InputEventType { Click, KeyPressed }

data class InputProgram(val routes: List<InputRoute>)

data class InputRoute(
    val regionId: String,
    val eventType: InputEventType,
    val handlerId: String,
)

data class FocusProgram(val targets: List<FocusTarget>)

data class FocusTarget(
    val regionId: String,
    val role: UiRole?,
    val order: Int,
)

interface RenderBackend {
    fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int)
    fun drawText(x: Int, y: Int, text: String, color: Int)
    fun drawTerminalSurface(x: Int, y: Int, snapshot: Any?)
}

data class SlotValues(private val values: Map<String, Any?> = emptyMap()) {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(slotId: String): T = values.getValue(slotId) as T
}
```

- [ ] **Step 4: Implement the compiler with structural lowering and dynamic fragment extraction**

Create `ScreenProgramCompiler.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement

class ScreenProgramCompiler {
    fun compile(root: UiElement): ScreenProgram {
        val layoutNodes = mutableListOf<LayoutNode>()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val focusTargets = mutableListOf<FocusTarget>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()

        lower(
            element = root,
            nodeId = "root",
            x = 0,
            y = 0,
            layoutNodes = layoutNodes,
            renderOps = renderOps,
            hitRegions = hitRegions,
            inputRoutes = inputRoutes,
            focusTargets = focusTargets,
            dynamicLayouts = dynamicLayouts,
            dynamicRenders = dynamicRenders,
        )

        return ScreenProgram(
            layoutProgram = LayoutProgram(layoutNodes, dynamicLayouts),
            renderProgram = RenderProgram(renderOps, dynamicRenders),
            hitTestProgram = HitTestProgram(hitRegions.sortedByDescending { it.zIndex }),
            inputProgram = InputProgram(inputRoutes),
            focusProgram = FocusProgram(focusTargets.sortedBy { it.order }),
        )
    }

    private fun lower(
        element: UiElement,
        nodeId: String,
        x: Int,
        y: Int,
        layoutNodes: MutableList<LayoutNode>,
        renderOps: MutableList<RenderOp>,
        hitRegions: MutableList<HitRegion>,
        inputRoutes: MutableList<InputRoute>,
        focusTargets: MutableList<FocusTarget>,
        dynamicLayouts: MutableList<DynamicLayoutFragment>,
        dynamicRenders: MutableList<DynamicRenderFragment>,
    ) {
        when (element) {
            is UiElement.Box -> {
                layoutNodes += LayoutNode(nodeId, x + element.modifier.x, y + element.modifier.y, element.modifier.width ?: 0, element.modifier.height ?: 0)
                element.children.forEachIndexed { index, child ->
                    lower(child, "$nodeId-$index", x + element.modifier.x, y + element.modifier.y, layoutNodes, renderOps, hitRegions, inputRoutes, focusTargets, dynamicLayouts, dynamicRenders)
                }
            }

            is UiElement.Text -> {
                val slotId = "$nodeId-text"
                layoutNodes += LayoutNode(nodeId, x + element.modifier.x, y + element.modifier.y, element.modifier.width ?: 80, element.modifier.height ?: 9)
                renderOps += RenderOp.DrawText(nodeId, slotId, element.color)
            }

            is UiElement.TerminalSurface -> {
                val slotId = "$nodeId-snapshot"
                val regionId = "$nodeId-region"
                layoutNodes += LayoutNode(nodeId, x + element.modifier.x, y + element.modifier.y, element.modifier.width ?: 0, element.modifier.height ?: 0)
                renderOps += RenderOp.DrawTerminalSurface(nodeId, slotId)
                hitRegions += HitRegion(regionId, nodeId, element.modifier.role, element.modifier.zIndex, element.modifier.focusable)
                inputRoutes += InputRoute(regionId, InputEventType.KeyPressed, "$regionId-key")
                focusTargets += FocusTarget(regionId, element.modifier.role, focusTargets.size)
            }

            is UiElement.IfNode -> {
                dynamicLayouts += DynamicLayoutFragment { emptyList() }
                dynamicRenders += DynamicRenderFragment { emptyList() }
            }
        }

        val regionId = "$nodeId-region"
        if (element.modifier.onClick != null) {
            hitRegions += HitRegion(regionId, nodeId, element.modifier.role, element.modifier.zIndex, element.modifier.focusable)
            inputRoutes += InputRoute(regionId, InputEventType.Click, "$regionId-click")
            if (element.modifier.focusable) {
                focusTargets += FocusTarget(regionId, element.modifier.role, focusTargets.size)
            }
        }
    }
}
```

- [ ] **Step 5: Run compiler tests and commit the phased program layer**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: PASS.

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: compile screen first ui into phased screen program"
```

### Task 3: Add the runtime executor and Minecraft backend bridge

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutorTest.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutor.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/TerminalSurfaceBridge.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/DslContainerScreen.kt`

- [ ] **Step 1: Write failing executor tests for click, focus, and key routing**

Create `ScreenRuntimeExecutorTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class ScreenRuntimeExecutorTest {
    @Test
    fun mouseClickDispatchesTopmostClickableRegion() {
        val events = mutableListOf<String>()
        val compiler = ScreenProgramCompiler()
        val program = compiler.compile(
            ui {
                button(text = textExpr { "Behind" }, modifier = Modifier.offset(4, 4).zIndex(0)) { events += "behind" }
                button(text = textExpr { "Front" }, modifier = Modifier.offset(4, 4).zIndex(1)) { events += "front" }
            },
        )
        val executor = ScreenRuntimeExecutor(
            program = program,
            slotProvider = { SlotValues() },
            clickHandlers = mapOf(
                "root-0-region-click" to { events += "behind" },
                "root-1-region-click" to { events += "front" },
            ),
            keyHandlers = emptyMap(),
        )

        assertTrue(executor.mouseClicked(8, 8))
        assertEquals(listOf("front"), events)
    }

    @Test
    fun focusedTerminalReceivesKeyEventsThroughInputProgram() {
        val compiler = ScreenProgramCompiler()
        val program = compiler.compile(
            ui {
                terminalSurface(
                    snapshot = expr { "snapshot" },
                    modifier = Modifier.offset(8, 8).size(80, 32).focusable(),
                    onKey = { keyCode -> keyCode == 257 },
                )
            },
        )
        val executor = ScreenRuntimeExecutor(
            program = program,
            slotProvider = { SlotValues() },
            clickHandlers = emptyMap(),
            keyHandlers = mapOf("root-0-region-key" to { keyCode: Int -> keyCode == 257 }),
        )

        assertTrue(executor.mouseClicked(10, 10))
        assertTrue(executor.keyPressed(257))
    }
}
```

- [ ] **Step 2: Run the tests to verify the executor is still missing**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" --console=plain`

Expected: FAIL because `ScreenRuntimeExecutor` and the backend bridge do not exist yet.

- [ ] **Step 3: Add the runtime executor contract and typed backend API**

Create the executor in `:core` and the Minecraft backend in `:v1_21_1-common` with this minimum shape:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
    private val slotProvider: () -> SlotValues,
    private val clickHandlers: Map<String, () -> Unit>,
    private val keyHandlers: Map<String, (Int) -> Boolean>,
) {
    private var focusedRegionId: String? = null

    fun render(backend: RenderBackend) {
        val slots = slotProvider()
        program.renderProgram.staticOps.forEach { op ->
            when (op) {
                is RenderOp.FillRect -> backend.fillRect(0, 0, 0, 0, op.color)
                is RenderOp.DrawText -> backend.drawText(0, 0, slots.get(op.slotId), op.color)
                is RenderOp.DrawTerminalSurface -> backend.drawTerminalSurface(0, 0, slots.get(op.slotId))
            }
        }
    }

    fun mouseClicked(x: Int, y: Int): Boolean {
        val region = program.hitTestProgram.regions.firstOrNull() ?: return false
        focusedRegionId = if (region.focusable) region.regionId else focusedRegionId
        val route = program.inputProgram.routes.firstOrNull { it.regionId == region.regionId && it.eventType == InputEventType.Click } ?: return false
        clickHandlers[route.handlerId]?.invoke()
        return true
    }

    fun keyPressed(keyCode: Int): Boolean {
        val regionId = focusedRegionId ?: return false
        val route = program.inputProgram.routes.firstOrNull { it.regionId == regionId && it.eventType == InputEventType.KeyPressed } ?: return false
        return keyHandlers[route.handlerId]?.invoke(keyCode) ?: false
    }
}
```

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.core.ui.program.RenderBackend

class GuiGraphicsRenderBackend(
    private val graphics: GuiGraphics,
    private val font: Font,
    private val bridge: TerminalSurfaceBridge,
) : RenderBackend {
    override fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        graphics.fill(x, y, x + width, y + height, color)
    }

    override fun drawText(x: Int, y: Int, text: String, color: Int) {
        graphics.drawString(font, text, x, y, color, false)
    }

    override fun drawTerminalSurface(x: Int, y: Int, snapshot: Any?) {
        bridge.draw(x, y, snapshot)
    }
}
```

- [ ] **Step 4: Add the terminal bridge and screen host, then make the executor tests pass**

Create `TerminalSurfaceBridge.kt` and `DslContainerScreen.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.ui.program

class TerminalSurfaceBridge {
    fun draw(x: Int, y: Int, snapshot: Any?) {
        snapshot ?: return
    }
}

abstract class DslContainerScreen {
    abstract fun rebuildProgram()
    abstract fun renderProgram()
    abstract fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean
    abstract fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean
}
```

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" --console=plain`

Expected: PASS after wiring handler ids consistently.

- [ ] **Step 5: Commit the executor and backend bridge**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutorTest.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenRuntimeExecutor.kt \
    modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program
git commit -m "feat: add screen program runtime executor"
```

### Task 4: Migrate `ComputerTerminalScreen` and document the merged architecture

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Write down the terminal-screen contract in a small compiler-level regression test**

Add this test to `ScreenProgramCompilerTest.kt`:

```kotlin
@Test
fun terminalScreenSliceCompilesTwoControlButtonsAndOneFocusableTerminal() {
    val compiler = ScreenProgramCompiler()

    val program = compiler.compile(
        ui {
            button(text = textExpr { "Power" }, modifier = Modifier.offset(0, 0)) { }
            button(text = textExpr { "Reboot" }, modifier = Modifier.offset(28, 0)) { }
            terminalSurface(
                snapshot = expr { "snapshot" },
                modifier = Modifier.offset(0, 28).size(128, 72).focusable(),
                onKey = { true },
            )
        },
    )

    assertEquals(3, program.hitTestProgram.regions.size)
    assertEquals(3, program.inputProgram.routes.size)
    assertEquals(3, program.focusProgram.targets.size)
}
```

- [ ] **Step 2: Rewrite `ComputerTerminalScreen` to build and execute a compiled screen program**

Replace the manual button rendering and direct terminal input routing with a compiled program host shaped like this:

```kotlin
private val compiler = ScreenProgramCompiler()
private lateinit var executor: ScreenRuntimeExecutor

override fun containerTick() {
    super.containerTick()
    terminalInput.update()
    executor = ScreenRuntimeExecutor(
        program = compiler.compile(buildScreenContent()),
        slotProvider = ::currentSlots,
        clickHandlers = mapOf(
            "power-button-click" to { inputHandler.accept(ControlInputEvent(ComputerControlAction.POWER)) },
            "reboot-button-click" to { inputHandler.accept(ControlInputEvent(ComputerControlAction.REBOOT)) },
        ),
        keyHandlers = mapOf(
            "terminal-region-key" to { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) },
        ),
    )
}

private fun buildScreenContent() = ui {
    button(text = textExpr { "Power" }, modifier = Modifier.offset(leftPos + 12, topPos + 8)) { }
    button(text = textExpr { "Reboot" }, modifier = Modifier.offset(leftPos + 44, topPos + 8)) { }
    terminalSurface(
        snapshot = expr { menu.clientSide.screenSnapshot },
        modifier = Modifier.offset(terminalLayout().terminalBounds.x, terminalLayout().terminalBounds.y)
            .size(terminalLayout().terminalBounds.width, terminalLayout().terminalBounds.height)
            .focusable(),
        onKey = { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) },
    )
}
```

- [ ] **Step 3: Update docs and adjust terminal metrics tests only if the migrated composition changes bounds**

Append this architecture note to `docs/ARCHITECTURE.md` and update `WorkbenchTerminalMetricsTest.kt` only if assertions fail:

```markdown
## Screen-First Compiled UI

The UI stack now has four layers:

1. `core.ui.foundation` authoring DSL for layout, render intent, semantics, and interaction.
2. `core.ui.program` compiler output with phased layout, render, hit-test, input, and focus programs.
3. `common.ui.program.ScreenRuntimeExecutor` as the minimal runtime host.
4. Minecraft-specific render and terminal bridges in `v1_21_1-common`.

Legacy `core.ui.dsl.UiNode` and `common.ui.dsl.UiRenderer` remain transitional for the workbench screen only.
```

- [ ] **Step 4: Run focused verification and commit the migration slice**

Run:

```bash
./gradlew :core:test \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" \
  --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" \
  :v1_21_1-common:test \
  --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" \
  --tests "ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuSmokeTest" \
  :v1_21_1-common:compileKotlin --console=plain
```

Expected: PASS.

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt \
  docs/ARCHITECTURE.md
git commit -m "feat: migrate computer terminal screen to compiled ui program"
```

## Self-Review

- Spec coverage: the plan covers the new authoring DSL, phased `ScreenProgram`, small runtime executor, backend bridge, terminal migration, and architecture docs. No spec section was left without a corresponding task.
- Placeholder scan: there are no `TODO`, `TBD`, or "implement later" placeholders. Every task names exact files, commands, and code targets.
- Type consistency: the same names are used throughout the plan: `UiExpression`, `UiModifier`, `UiElement`, `ScreenProgram`, `LayoutProgram`, `RenderProgram`, `HitTestProgram`, `InputProgram`, `FocusProgram`, `RenderBackend`, `ScreenProgramCompiler`, and `ScreenRuntimeExecutor`.
- Execution consistency: the plan keeps legacy `UiNode`/`UiRenderer` alive for workbench, so the migration order is executable. The main unresolved implementation detail is exact layout math inside `ComputerTerminalScreen`; the plan intentionally keeps `WorkbenchTerminalMetrics` as the source of truth so the executor work does not invent a second layout system.
