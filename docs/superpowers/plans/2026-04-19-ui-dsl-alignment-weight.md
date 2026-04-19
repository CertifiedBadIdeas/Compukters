# UI DSL Alignment And Weight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the latest compiled screen-first DSL so authors can use padding, alignment, and weight with `box`, `row`, and `column` containers.

**Architecture:** Keep the current compiled `ScreenProgram` runtime shape, but insert a pure layout-resolution pass between the authoring tree and the existing lowering stages. Expand `core.ui.foundation` with layout modifiers and containers, add a focused `UiLayoutResolver`, then lower the resolved bounds into the existing render, hit-test, input, and focus programs.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, `:core` unit tests, focused `:v1_21_1-common:compileKotlin`, existing `ScreenProgramCompiler`, `ScreenRuntimeExecutor`, and `kotlin.test`.

---

## Scope Check

This plan is one focused subsystem:

- extend the authoring DSL with `padding`, `align`, `weight`, `row`, and `column`
- resolve bounds through a pure layout pass
- keep the executor/runtime architecture unchanged

It does not migrate any existing screen to the new layout primitives.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Modify | Add padding, alignment, and weight modifiers |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt` | Modify | Add `Row` and `Column` authoring nodes plus DSL builders |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt` | Create | Pure layout resolution for box/row/column |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Modify | Use resolved layout bounds before lowering render/hit/input/focus |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt` | Create | Layout-specific regression tests for padding/alignment/weight |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Modify | Ensure compiled programs reuse resolved bounds correctly |

### Task 1: Expand the authoring DSL surface

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt`

- [ ] **Step 1: Write the failing layout-surface tests**

Create `UiLayoutResolverTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class UiLayoutResolverTest {
    @Test
    fun boxCentersChildInsidePaddedBounds() {
        val root = ui {
            box(modifier = Modifier.size(200, 120).padding(10)) {
                text(
                    value = textExpr { "Centered" },
                    modifier = Modifier.size(80, 20).align(UiAlignment.Center),
                )
            }
        }

        val layout = UiLayoutResolver(rootWidth = 200, rootHeight = 120).resolve(root)

        assertEquals(LayoutNode("root-0-0", 60, 50, 80, 20), layout.getValue("root-0-0"))
    }

    @Test
    fun rowDistributesRemainingWidthAcrossWeightedChildren() {
        val root = ui {
            row(modifier = Modifier.size(120, 20)) {
                text(value = textExpr { "A" }, modifier = Modifier.size(20, 20))
                text(value = textExpr { "B" }, modifier = Modifier.weight(1f).size(0, 20))
                text(value = textExpr { "C" }, modifier = Modifier.weight(2f).size(0, 20))
            }
        }

        val layout = UiLayoutResolver(rootWidth = 120, rootHeight = 20).resolve(root)

        assertEquals(LayoutNode("root-0-1", 20, 0, 33, 20), layout.getValue("root-0-1"))
        assertEquals(LayoutNode("root-0-2", 53, 0, 67, 20), layout.getValue("root-0-2"))
    }
}
```

- [ ] **Step 2: Run the new tests to verify the layout API is missing**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL with unresolved symbols for `UiAlignment`, `padding`, `weight`, `row`, and `UiLayoutResolver`.

- [ ] **Step 3: Add minimal layout modifiers and container nodes**

Update `UiModifier.kt` so it exposes alignment, padding, and weight metadata:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.foundation

enum class UiRole {
    Button,
    TerminalSurface,
}

enum class UiAlignment {
    Start,
    Center,
    End,
    Stretch,
}

data class UiPadding(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class UiModifier(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val zIndex: Int = 0,
    val focusable: Boolean = false,
    val role: UiRole? = null,
    val onClick: (() -> Unit)? = null,
    val padding: UiPadding = UiPadding(),
    val alignment: UiAlignment? = null,
    val weight: Float? = null,
) {
    fun offset(x: Int, y: Int): UiModifier = copy(x = this.x + x, y = this.y + y)
    fun size(width: Int, height: Int): UiModifier = copy(width = width, height = height)
    fun zIndex(value: Int): UiModifier = copy(zIndex = value)
    fun focusable(): UiModifier = copy(focusable = true)
    fun role(value: UiRole): UiModifier = copy(role = value)
    fun clickable(onClick: () -> Unit): UiModifier = copy(onClick = onClick)
    fun align(value: UiAlignment): UiModifier = copy(alignment = value)
    fun weight(value: Float): UiModifier {
        require(value > 0f)
        return copy(weight = value)
    }
    fun padding(all: Int): UiModifier = padding(all, all, all, all)
    fun padding(horizontal: Int, vertical: Int): UiModifier = padding(horizontal, vertical, horizontal, vertical)
    fun padding(left: Int, top: Int, right: Int, bottom: Int): UiModifier {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
        return copy(padding = UiPadding(left, top, right, bottom))
    }

    companion object {
        val Empty = UiModifier()
    }
}

val Modifier: UiModifier = UiModifier.Empty
```

Update `UiElement.kt` so the DSL has `row` and `column` builders:

```kotlin
sealed interface UiElement {
    val modifier: UiModifier

    data class Box(override val modifier: UiModifier = Modifier, val children: List<UiElement>) : UiElement
    data class Row(override val modifier: UiModifier = Modifier, val children: List<UiElement>) : UiElement
    data class Column(override val modifier: UiModifier = Modifier, val children: List<UiElement>) : UiElement
    data class Text(override val modifier: UiModifier = Modifier, val value: UiExpression<String>, val color: Int = 0xFFFFFF) : UiElement
    data class TerminalSurface(override val modifier: UiModifier = Modifier, val snapshot: UiExpression<Any?>, val onFocus: () -> Unit = {}, val onKey: (Int) -> Boolean = { false }) : UiElement
    data class IfNode(override val modifier: UiModifier = Modifier, val condition: UiExpression<Boolean>, val children: List<UiElement>) : UiElement
}

class UiScope {
    private val children = mutableListOf<UiElement>()

    fun box(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Box(modifier, UiScope().apply(block).build())
    }

    fun row(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Row(modifier, UiScope().apply(block).build())
    }

    fun column(modifier: UiModifier = Modifier, block: UiScope.() -> Unit) {
        children += UiElement.Column(modifier, UiScope().apply(block).build())
    }
}
```

- [ ] **Step 4: Run the tests again to move the failure to layout resolution**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL because `UiLayoutResolver` does not exist yet.

- [ ] **Step 5: Commit the DSL surface expansion**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt
git commit -m "feat: add layout modifiers to ui foundation"
```

### Task 2: Add pure layout resolution for box, row, and column

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt`

- [ ] **Step 1: Expand the tests to lock padding and ignored weight behavior**

Append this test to `UiLayoutResolverTest.kt`:

```kotlin
@Test
fun boxIgnoresWeightAndUsesAlignedPlacement() {
    val root = ui {
        box(modifier = Modifier.size(100, 100).padding(10)) {
            text(
                value = textExpr { "Weighted" },
                modifier = Modifier.size(20, 10).weight(1f).align(UiAlignment.End),
            )
        }
    }

    val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 100).resolve(root)

    assertEquals(LayoutNode("root-0-0", 70, 80, 20, 10), layout.getValue("root-0-0"))
}
```

- [ ] **Step 2: Run the tests to confirm the resolver is still missing**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL with `UiLayoutResolver` unresolved.

- [ ] **Step 3: Implement the minimal pure layout resolver**

Create `UiLayoutResolver.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.UiModifier

class UiLayoutResolver(
    private val rootWidth: Int,
    private val rootHeight: Int,
) {
    fun resolve(root: UiElement): Map<String, LayoutNode> {
        val resolved = linkedMapOf<String, LayoutNode>()
        resolveNode(root, "root", 0, 0, rootWidth, rootHeight, resolved)
        return resolved
    }

    private fun resolveNode(
        element: UiElement,
        nodeId: String,
        parentX: Int,
        parentY: Int,
        parentWidth: Int,
        parentHeight: Int,
        resolved: MutableMap<String, LayoutNode>,
    ) {
        val width = element.modifier.width ?: parentWidth
        val height = element.modifier.height ?: parentHeight
        val alignedX = alignedPrimary(parentX, parentWidth, width, element.modifier.alignment)
        val alignedY = alignedCross(parentY, parentHeight, height, element.modifier.alignment)
        val x = alignedX + element.modifier.x
        val y = alignedY + element.modifier.y

        resolved[nodeId] = LayoutNode(nodeId, x, y, width, height)

        when (element) {
            is UiElement.Box -> resolveBoxChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.Row -> resolveRowChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.Column -> resolveColumnChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.IfNode -> element.children.forEachIndexed { index, child ->
                resolveNode(child, "$nodeId-$index", x, y, width, height, resolved)
            }
            is UiElement.Text, is UiElement.TerminalSurface -> Unit
        }
    }

    private fun resolveBoxChildren(children: List<UiElement>, nodeId: String, x: Int, y: Int, width: Int, height: Int, modifier: UiModifier, resolved: MutableMap<String, LayoutNode>) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom
        children.forEachIndexed { index, child ->
            resolveNode(child, "$nodeId-$index", contentX, contentY, contentWidth, contentHeight, resolved)
        }
    }

    private fun resolveRowChildren(children: List<UiElement>, nodeId: String, x: Int, y: Int, width: Int, height: Int, modifier: UiModifier, resolved: MutableMap<String, LayoutNode>) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom
        val fixedWidth = children.filter { it.modifier.weight == null }.sumOf { it.modifier.width ?: 0 }
        val totalWeight = children.sumOf { (it.modifier.weight ?: 0f).toDouble() }.toFloat()
        var cursorX = contentX
        children.forEachIndexed { index, child ->
            val childWidth = if (child.modifier.weight != null && totalWeight > 0f) ((contentWidth - fixedWidth) * (child.modifier.weight / totalWeight)).toInt() else (child.modifier.width ?: 0)
            val childHeight = child.modifier.height ?: contentHeight
            resolveNode(child, "$nodeId-$index", cursorX, contentY, childWidth, childHeight, resolved)
            cursorX += childWidth
        }
    }

    private fun resolveColumnChildren(children: List<UiElement>, nodeId: String, x: Int, y: Int, width: Int, height: Int, modifier: UiModifier, resolved: MutableMap<String, LayoutNode>) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom
        val fixedHeight = children.filter { it.modifier.weight == null }.sumOf { it.modifier.height ?: 0 }
        val totalWeight = children.sumOf { (it.modifier.weight ?: 0f).toDouble() }.toFloat()
        var cursorY = contentY
        children.forEachIndexed { index, child ->
            val childHeight = if (child.modifier.weight != null && totalWeight > 0f) ((contentHeight - fixedHeight) * (child.modifier.weight / totalWeight)).toInt() else (child.modifier.height ?: 0)
            val childWidth = child.modifier.width ?: contentWidth
            resolveNode(child, "$nodeId-$index", contentX, cursorY, childWidth, childHeight, resolved)
            cursorY += childHeight
        }
    }

    private fun alignedPrimary(parentX: Int, parentWidth: Int, width: Int, alignment: UiAlignment?): Int =
        when (alignment) {
            UiAlignment.Center -> parentX + (parentWidth - width) / 2
            UiAlignment.End -> parentX + parentWidth - width
            else -> parentX
        }

    private fun alignedCross(parentY: Int, parentHeight: Int, height: Int, alignment: UiAlignment?): Int =
        when (alignment) {
            UiAlignment.Center -> parentY + (parentHeight - height) / 2
            UiAlignment.End -> parentY + parentHeight - height
            else -> parentY
        }
}
```

- [ ] **Step 4: Run the resolver tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit the pure layout resolver**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt
git commit -m "feat: resolve layout bounds for ui containers"
```

### Task 3: Wire resolved bounds into the compiled program path

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Add a failing compiler regression that depends on resolved bounds**

Append this test to `ScreenProgramCompilerTest.kt`:

```kotlin
@Test
fun alignedChildBoundsFlowIntoHitRegionsAndRenderLayout() {
    val compiler = ScreenProgramCompiler(rootWidth = 200, rootHeight = 120)

    val program = compiler.compile(
        ui {
            box(modifier = Modifier.size(200, 120).padding(10)) {
                button(
                    text = textExpr { "Centered" },
                    modifier = Modifier.size(80, 20).align(UiAlignment.Center),
                ) { }
            }
        },
    )

    assertEquals(LayoutNode("root-0-0", 60, 50, 80, 20), program.layoutProgram.staticNodes.single { it.nodeId == "root-0-0" })
    assertEquals("root-0-0", program.hitTestProgram.regions.single().nodeId)
}
```

- [ ] **Step 2: Run the compiler tests to verify the compiler still uses old direct lowering**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL because `ScreenProgramCompiler` does not yet resolve container bounds.

- [ ] **Step 3: Update the compiler to consume resolved bounds**

Refactor `ScreenProgramCompiler.kt` so it resolves all layout first and then lowers with those bounds:

```kotlin
class ScreenProgramCompiler(
    private val rootWidth: Int = 0,
    private val rootHeight: Int = 0,
) {
    fun compile(root: UiElement): ScreenProgram {
        val resolvedLayout = UiLayoutResolver(rootWidth, rootHeight).resolve(root)
        val layoutNodes = resolvedLayout.values.toMutableList()
        val renderOps = mutableListOf<RenderOp>()
        val hitRegions = mutableListOf<HitRegion>()
        val inputRoutes = mutableListOf<InputRoute>()
        val focusTargets = mutableListOf<FocusTarget>()
        val dynamicLayouts = mutableListOf<DynamicLayoutFragment>()
        val dynamicRenders = mutableListOf<DynamicRenderFragment>()

        lower(
            element = root,
            nodeId = "root",
            resolvedLayout = resolvedLayout,
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
            focusProgram = FocusProgram(focusTargets),
        )
    }
}
```

The existing `lower(...)` should stop deriving `x`, `y`, `width`, and `height` from modifiers and should instead read them from `resolvedLayout.getValue(nodeId)`.

- [ ] **Step 4: Run focused verification**

Run:

```bash
./gradlew :core:test \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" \
  :v1_21_1-common:compileKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit the compiler integration**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: compile aligned and weighted ui layouts"
```

## Self-Review

- Spec coverage: the plan covers DSL API growth, box/row/column layout semantics, padding/alignment/weight behavior, a dedicated layout pass, and focused verification.
- Placeholder scan: no `TODO`, `TBD`, or vague “add tests later” steps remain.
- Type consistency: the plan consistently uses `UiAlignment`, `UiPadding`, `UiLayoutResolver`, and `weight(value: Float)` without reintroducing the deferred `fill` parameter.
- Execution consistency: the plan keeps the runtime executor untouched and scopes verification to existing modules and commands available in this worktree.