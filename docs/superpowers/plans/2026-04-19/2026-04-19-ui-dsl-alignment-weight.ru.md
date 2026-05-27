# План реализации alignment и weight для UI DSL

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Расширить последний compiled screen-first DSL так, чтобы автор мог использовать padding, alignment и weight вместе с контейнерами `box`, `row` и `column`.

**Architecture:** Сохранить текущую compiled runtime shape `ScreenProgram`, но вставить pure layout-resolution pass между authoring tree и существующими lowering stages. Расширить `core.ui.foundation` layout-модификаторами и контейнерами, добавить отдельный `UiLayoutResolver`, а затем lower-ить уже resolved bounds в существующие render, hit-test, input и focus programs.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, unit-тесты `:core`, focused `:v1_21_1-common:compileKotlin`, существующие `ScreenProgramCompiler`, `ScreenRuntimeExecutor` и `kotlin.test`.

---

## Scope Check

Этот план покрывает один сфокусированный subsystem:

- расширить authoring DSL через `padding`, `align`, `weight`, `row` и `column`
- вычислять bounds через pure layout pass
- оставить executor/runtime architecture без изменений

Он не мигрирует никакой существующий screen на новые layout primitives.

## Структура файлов

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Modify | Добавить modifiers для padding, alignment и weight |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt` | Modify | Добавить authoring nodes `Row` и `Column` плюс DSL builders |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt` | Create | Pure layout resolution для box/row/column |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Modify | Использовать resolved layout bounds перед lowering render/hit/input/focus |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt` | Create | Layout-specific regression tests для padding/alignment/weight |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Modify | Проверить, что compiled programs используют resolved bounds |

### Task 1: Расширить authoring DSL surface

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt`

- [ ] **Step 1: Сначала написать падающие tests для layout-surface**

Создай `UiLayoutResolverTest.kt`:

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

- [ ] **Step 2: Запустить тесты и убедиться, что layout API ещё отсутствует**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL с unresolved symbols для `UiAlignment`, `padding`, `weight`, `row` и `UiLayoutResolver`.

- [ ] **Step 3: Добавить минимальные layout modifiers и container nodes**

Обнови `UiModifier.kt`, чтобы он содержал alignment, padding и weight metadata:

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

Обнови `UiElement.kt`, чтобы DSL имел builders для `row` и `column`:

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

- [ ] **Step 4: Снова прогнать тесты, чтобы следующий fail был уже в layout resolution**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL, потому что `UiLayoutResolver` ещё не существует.

- [ ] **Step 5: Закоммитить расширение DSL surface**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt
git commit -m "feat: add layout modifiers to ui foundation"
```

### Task 2: Добавить pure layout resolution для box, row и column

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt`

- [ ] **Step 1: Расширить тесты, чтобы зафиксировать padding и ignored weight behavior**

Добавь этот test в `UiLayoutResolverTest.kt`:

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

- [ ] **Step 2: Запустить тесты и подтвердить, что resolver всё ещё отсутствует**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: FAIL с unresolved `UiLayoutResolver`.

- [ ] **Step 3: Реализовать минимальный pure layout resolver**

Создай `UiLayoutResolver.kt`:

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

- [ ] **Step 4: Прогнать resolver tests и убедиться, что они зелёные**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" --console=plain`

Expected: PASS.

- [ ] **Step 5: Закоммитить pure layout resolver**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolver.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLayoutResolverTest.kt
git commit -m "feat: resolve layout bounds for ui containers"
```

### Task 3: Подключить resolved bounds к compiled program path

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Добавить падающий compiler regression test, зависящий от resolved bounds**

Добавь этот test в `ScreenProgramCompilerTest.kt`:

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

- [ ] **Step 2: Запустить compiler tests и убедиться, что compiler всё ещё использует старый direct lowering**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL, потому что `ScreenProgramCompiler` ещё не использует container bounds resolution.

- [ ] **Step 3: Обновить compiler так, чтобы он потреблял resolved bounds**

Перепиши `ScreenProgramCompiler.kt`, чтобы он сначала резолвил layout, а потом lower-ил по готовым bounds:

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

Существующий `lower(...)` должен перестать вычислять `x`, `y`, `width` и `height` из modifiers и вместо этого читать их из `resolvedLayout.getValue(nodeId)`.

- [ ] **Step 4: Прогнать focused verification**

Run:

```bash
./gradlew :core:test \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.UiLayoutResolverTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" \
  :v1_21_1-common:compileKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 5: Закоммитить compiler integration**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: compile aligned and weighted ui layouts"
```

## Self-Review

- Spec coverage: план покрывает рост DSL API, semantics для box/row/column, поведение padding/alignment/weight, dedicated layout pass и focused verification.
- Placeholder scan: нет `TODO`, `TBD` и размытых шагов вида “добавить тесты потом”.
- Type consistency: план последовательно использует `UiAlignment`, `UiPadding`, `UiLayoutResolver` и `weight(value: Float)` без повторного появления отложенного `fill`.
- Execution consistency: план не трогает runtime executor и держит verification в рамках существующих модулей и команд этого worktree.