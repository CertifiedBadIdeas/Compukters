# План реализации основы compiled UI DSL и миграции terminal renderer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить первый production-ready slice нового compiled UI DSL: ввести pipeline `DSL -> Layout IR -> Render IR -> ScreenProgram` и перевести на него общий terminal renderer.

**Architecture:** Реализовать маленький и тестируемый compiler в `modules/core`, который поддерживает только примитивы, нужные для terminal surface и его chrome, затем добавить в `v1_21_1-common` execution adapter, который исполняет compiled program через `GuiGraphics`. Первая итерация намеренно узкая: только terminal panel, общая для computer и workbench screens, а полная миграция workbench chrome откладывается в следующий план.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, существующие тестовые наборы `:core` и `:v1_21_1-common`, Minecraft `GuiGraphics`, текущие terminal layout и font renderer.

---

## Проверка области плана

Дизайн-спека описывает долгосрочный UI framework, но этот план сознательно сужает реализацию до одного рабочего slice:

- compiled UI core model
- terminal-specific primitive и bindings
- native renderer adapter для compiled render ops
- миграция `WorkbenchTerminalRenderer` и обоих экранов, которые уже его используют

Откладывается в следующие планы:

- полная миграция workbench shell
- миграция generic editor и inventory chrome
- input DSL и focus DSL
- advanced overlays и popup systems

## Структура файлов

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLength.kt` | Create | Типизированные relative size values: pixels, percent, fill и weight для первого compiler slice |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiBinding.kt` | Create | Типизированные dynamic slot declarations для текста, булевых флагов, цветов и terminal snapshots |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutNode.kt` | Create | Минимальная author-facing модель layout nodes для `box`, `column`, `stack`, `text`, `rect` и `terminalSurface` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutIr.kt` | Create | Скомпилированные структуры layout IR и metadata для static/dynamic fragments |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderOp.kt` | Create | Модель специализированных render ops, включая `DrawTerminalSurfaceOp` и typed slot references |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt` | Create | Immutable compiled screen program со static layout, dynamic fragments и render ops |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Create | Компилятор из DSL nodes в layout IR, render IR и `ScreenProgram` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgram.kt` | Create | Узкий authoring API для terminal panel, используемой общим renderer |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Create | Core-тесты компиляции для static flattening и binding slot classification |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgramTest.kt` | Create | Terminal-specific program tests, заменяющие ожидания старого `UiNode` DSL |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt` | Delete | Старый builder test удаляется после миграции |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/UiNode.kt` | Delete | Старый generic UI node DSL удаляется после исчезновения callers |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt` | Delete | Удаляется старый terminal-specific node builder |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/RenderBackend.kt` | Create | Маленький native backend interface, чтобы исполнение render ops оставалось тестируемым без `GuiGraphics` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt` | Create | Реализация `RenderBackend` на базе Minecraft `GuiGraphics` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRenderer.kt` | Create | Runtime executor для compiled render ops и dynamic slots |
| `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt` | Create | Тесты порядка исполнения ops и делегирования terminal-op через fake backend |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt` | Modify | Замена старого `UiNode` pipeline на compiled program creation и execution |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Modify | Сохранить screen API стабильным и делегировать рендер новому compiled terminal renderer |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt` | Modify | Перевести embedded terminal rendering на новый compiled path |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/UiRenderer.kt` | Delete | Удалить старый node renderer после исчезновения всех call sites |

### Task 1: Ввести core-модель ScreenProgram

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiLength.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/UiBinding.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/LayoutIr.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/RenderOp.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgram.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt`

- [ ] **Step 1: Сначала написать падающие тесты формы компилятора**

Создай `ScreenProgramCompilerTest.kt` с такими проверками:

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

- [ ] **Step 2: Запустить точечный тест и убедиться, что он падает**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: FAIL, потому что package `core.ui.program` и API компилятора ещё не существуют.

- [ ] **Step 3: Создать typed program model files с минимальным API, достаточным для тестов**

Добавь эти core-типы.

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

- [ ] **Step 4: Создать минимальный compiler и authoring nodes, нужные тестам**

Добавь `LayoutNode.kt` и `ScreenProgramCompiler.kt` с намеренно узкой поверхностью:

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

- [ ] **Step 5: Снова запустить compiler-shape test**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt
git commit -m "feat: add compiled ui screen program model"
```

### Task 2: Добавить dedicated terminal panel program в core

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgram.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/TerminalPanelProgramTest.kt`
- Delete: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/UiNode.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilder.kt`

- [ ] **Step 1: Написать падающие terminal-program tests до создания нового authoring API**

Создай `TerminalPanelProgramTest.kt`:

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

- [ ] **Step 2: Запустить точечный terminal-program test и убедиться, что он падает**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.TerminalPanelProgramTest" --console=plain`

Expected: FAIL, потому что `terminalPanelProgram` и компиляция `DrawTerminalSurface` ещё не существуют.

- [ ] **Step 3: Расширить core op model first-class terminal primitive'ом**

Обнови `RenderOp.kt` и добавь `TerminalPanelProgram.kt`:

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

- [ ] **Step 4: Заменить старые terminal-node tests и удалить старые builder files, когда новые тесты станут зелёными**

Удаляй старые `core.ui.dsl` terminal builder files в том же change, где `TerminalPanelProgramTest` уже green. Новое ожидание вместо `List<UiNode>` — это `ScreenProgram` data.

- [ ] **Step 5: Запустить сфокусированные core UI tests**

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

### Task 3: Добавить тестируемый native render backend в `v1_21_1-common`

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/RenderBackend.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRenderer.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt`

- [ ] **Step 1: Написать падающий renderer-execution test до wiring Minecraft classes**

Создай `ScreenProgramRendererTest.kt`:

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

- [ ] **Step 2: Запустить точечный common-module test и убедиться, что он падает**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" --console=plain`

Expected: FAIL, потому что backend abstraction и renderer ещё не существуют.

- [ ] **Step 3: Добавить backend abstraction и program executor**

Создай `RenderBackend.kt` и `ScreenProgramRenderer.kt`:

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

- [ ] **Step 4: Добавить `GuiGraphics` backend adapter и сохранить делегирование terminal drawing в текущий font renderer**

Создай `GuiGraphicsRenderBackend.kt`:

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

- [ ] **Step 5: Запустить новый common renderer test и собрать модуль**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program \
        modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/program/ScreenProgramRendererTest.kt
git commit -m "feat: add compiled ui program renderer backend"
```

### Task 4: Перевести общий terminal renderer и удалить старый runtime path

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/UiRenderer.kt`

- [ ] **Step 1: Написать падающий regression test на contract общего terminal renderer**

Добавь такой test в `ScreenProgramRendererTest.kt`, чтобы terminal-specific binding surface был зафиксирован до миграции:

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

- [ ] **Step 2: Запустить точечный regression test и убедиться, что он падает до миграции**

Run: `./gradlew :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest.executesTerminalOpFromCompiledPanelProgram" --console=plain`

Expected: FAIL, потому что текущая terminal program ещё не bind'ит реальный terminal op через общий renderer path.

- [ ] **Step 3: Переделать `WorkbenchTerminalRenderer`, чтобы он компилировал программу один раз на signature layout и рендерил через `ScreenProgramRenderer`**

Замени старый node-based path в `WorkbenchTerminalRenderer.kt` на cache, ключом которого будут panel geometry и тексты:

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

Затем исполняй программу так:

```kotlin
val bindings = mapOf(
    "terminal.status" to statusText,
    "terminal.snapshot" to activeSnapshot,
    "terminal.focused" to focused,
    "terminal.showFocusHint" to showFocusHint,
)

ScreenProgramRenderer(GuiGraphicsRenderBackend(graphics, font)).render(program, bindings)
```

- [ ] **Step 4: Сохранить оба screen-класса стабильными и удалить старый UI-node renderer path, когда все call sites соберутся**

`ComputerTerminalScreen.kt` и `WorkbenchEditorScreen.kt` должны продолжать вызывать `WorkbenchTerminalRenderer.render(...)` с тем же public signature. Меняется только внутренняя реализация. После того как оба экрана соберутся, удали `common/ui/dsl/UiRenderer.kt`, потому что compiled renderer его заменяет.

- [ ] **Step 5: Запустить сфокусированную проверку core и common UI slices**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.*" :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.ui.program.ScreenProgramRendererTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 6: Запустить существующие screen-adjacent smoke tests**

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

- Покрытие спеки сознательно сужено до первого implementation slice, который описан в migration section исходного дизайна.
- В плане нет `TODO` или `TBD` placeholders.
- Naming консистентен вокруг `ScreenProgram`, `ScreenProgramCompiler`, `RenderOp` и `terminalPanelProgram`.
- План предполагает, что `:core:test` и `:v1_21_1-common:test` являются валидными module tasks, что совпадает с существующими конвенциями планов в проекте.
- Следующий план должен покрыть полную миграцию workbench shell после стабилизации этого slice.