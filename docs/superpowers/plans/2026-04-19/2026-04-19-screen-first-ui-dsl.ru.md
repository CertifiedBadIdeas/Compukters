# План реализации screen-first единого UI DSL

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить первый рабочий slice нового screen-first unified UI DSL: ввести минимальный interactive UI foundation и переписать на нём `ComputerTerminalScreen`.

**Architecture:** Держать новую authoring surface вокруг `DslScreen`/`DslContainerScreen` host и маленького runtime, который вместе владеет layout, drawing, hit testing, focus и input routing. Не строить compiler pipeline, renderer/backend abstraction или binding-slot API. Legacy `UiNode`/`UiRenderer` код временно оставить для `WorkbenchEditorScreen`, но считать его миграционным fallback, а не целевой архитектурой.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, unit-тесты `:core`, компиляция и smoke-тесты `:v1_21_1-common`, Minecraft `GuiGraphics`, существующие helpers для terminal metrics и input-controller.

---

## Проверка области плана

Этот план намеренно реализует один полный вертикальный slice:

- новый core UI runtime для layout + draw + interaction
- Minecraft screen host для этого runtime
- первый набор interactive primitive, способный выразить кнопки и terminal surface
- прямое переписывание `ComputerTerminalScreen`

Этот план **не** переписывает `WorkbenchEditorScreen`. Он остаётся на legacy path до тех пор, пока terminal slice не докажет правильность API.

## Структура файлов

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt` | Create | Типизированная dynamic value surface для scalar и structural expression |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Create | Modifier chain для layout, appearance, click, focus, key input и visibility |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt` | Create | Element model для `box`, `column`, `text`, `button`, `terminalSurface` и `custom` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiFrame.kt` | Create | Flattened frame model с draw-командами, bounds и interaction regions |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntime.kt` | Create | Layout pass, frame building, hit testing, focus ownership и event dispatch |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt` | Create | Runtime-тесты для clickable button, focusable terminal surface и conditional nodes |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt` | Delete | Legacy render-only test заменяется runtime-level тестами |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/DslContainerScreen.kt` | Create | Minecraft screen host, владеющий одним UI runtime instance |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/GuiGraphicsFrameRenderer.kt` | Create | Minecraft renderer для `UiFrame.DrawCommand` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/TerminalSurfaceBridge.kt` | Create | Bridge от terminal primitive callbacks к `WorkbenchTerminalInputController` и `FixedWidthFontRenderer` |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` | Modify | Замена manual render/input orchestration на новый DSL host |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/render/WorkbenchTerminalRenderer.kt` | Keep | Legacy fallback для workbench до следующей переписи |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt` | Modify | При необходимости переякорить ожидания terminal bounds вокруг новой композиции button/terminal |
| `docs/ARCHITECTURE.md` | Modify | Документировать новый screen-first foundation и сосуществование legacy |

### Task 1: Ввести core interactive UI runtime

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiExpression.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiElement.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiFrame.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntime.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt`
- Delete: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt`

- [ ] **Step 1: Сначала написать падающие runtime-тесты**

Создай `UiRuntimeTest.kt`:

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

- [ ] **Step 2: Запустить точечный тест и убедиться, что он падает**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --console=plain`

Expected: FAIL, потому что package `core.ui.foundation` и runtime-типы ещё не существуют.

- [ ] **Step 3: Добавить core expression, modifier, element и frame model**

Создай `UiExpression.kt`, `UiModifier.kt`, `UiElement.kt` и `UiFrame.kt` с таким минимальным API:

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

- [ ] **Step 4: Реализовать минимальный runtime и удалить legacy render-only test**

Создай `UiRuntime.kt` и удали `TerminalUiBuilderTest.kt`:

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

- [ ] **Step 5: Запустить core runtime tests и закоммитить foundation skeleton**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --console=plain`

Expected: PASS.

Commit:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/dsl/TerminalUiBuilderTest.kt
git commit -m "feat: add screen-first ui runtime foundation"
```

### Task 2: Добавить Minecraft screen host и frame renderer

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/DslContainerScreen.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/GuiGraphicsFrameRenderer.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation/TerminalSurfaceBridge.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`

- [ ] **Step 1: Добавить regression frame-shape test для terminal screen slice**

Добавь такой test в `UiRuntimeTest.kt`:

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

- [ ] **Step 2: Запустить test и зафиксировать frame shape до добавления Minecraft host**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest.terminalScreenFrameContainsButtonsAndTerminalSurface" --console=plain`

Expected: PASS.

- [ ] **Step 3: Добавить Minecraft host classes**

Создай `DslContainerScreen.kt`, `GuiGraphicsFrameRenderer.kt` и `TerminalSurfaceBridge.kt`:

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

- [ ] **Step 4: Расширить runtime test и собрать Minecraft host slice**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS для core-тестов и успешная Kotlin compilation для новых host classes.

- [ ] **Step 5: Закоммитить bridge screen host**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/foundation \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt
git commit -m "feat: add minecraft host for screen-first ui runtime"
```

### Task 3: Переписать `ComputerTerminalScreen` на новом foundation

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt`
- Test: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/MenuSideClientTest.kt`

- [ ] **Step 1: Добавить regression contract test для композиции terminal screen**

Добавь такой test в `UiRuntimeTest.kt`:

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

- [ ] **Step 2: Запустить test и убедиться, что composition contract зафиксирован до screen wiring**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest.computerTerminalScreenContractUsesTwoButtonsAndOneFocusableTerminal" --console=plain`

Expected: PASS в `:core` до того, как ты тронешь Minecraft screen code.

- [ ] **Step 3: Заменить manual terminal rendering path в `ComputerTerminalScreen.kt`**

Перепиши `ComputerTerminalScreen.kt` так, чтобы он наследовался от нового host и собирал одно DSL tree:

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

- [ ] **Step 4: Собрать и прогнать regression-safe test set**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" --tests "ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetricsTest" :v1_21_1-common:test --tests "ru.lazyhat.compukterkraft.common.computer.menu.MenuSideClientTest" --console=plain`

Expected: PASS.

- [ ] **Step 5: Закоммитить первый переписанный screen**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiRuntimeTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/gui/WorkbenchTerminalMetricsTest.kt
git commit -m "feat: rewrite computer terminal screen with unified ui dsl"
```

### Task 4: Задокументировать новую архитектуру и сосуществование legacy

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Обновить архитектурный документ после приземления кода**

Добавь такой раздел в `docs/ARCHITECTURE.md`:

```md
### Screen-First UI Foundation

The primary UI authoring surface is now a screen-hosted DSL rather than a render-only builder pipeline.

- `core.ui.foundation` owns the Minecraft-agnostic UI runtime, frame model, layout, and interaction routing.
- `common.ui.foundation` owns Minecraft screen hosting and `GuiGraphics` rendering.
- `ComputerTerminalScreen` is the first screen migrated to the new foundation.
- Legacy `core.ui.dsl` and `common.ui.render.WorkbenchTerminalRenderer` remain temporarily for workbench migration only.
```

- [ ] **Step 2: Проверить docs и compile вместе**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.foundation.UiRuntimeTest" :v1_21_1-common:compileKotlin --console=plain`

Expected: PASS.

- [ ] **Step 3: Закоммитить архитектурную заметку**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: record screen-first ui foundation architecture"
```

## Чеклист самопроверки

- План нигде не вводит renderer/backend abstraction как author-facing concept.
- План держит первый slice сфокусированным на `ComputerTerminalScreen` и не разрастается скрытно в полный workbench rewrite.
- Каждая test command существует в `:core` или `:v1_21_1-common` и согласована с файлами, которые трогаются в task.
- Legacy workbench paths остаются собираемыми, пока вводится новый screen-first slice.
- Старое compiled-DSL направление от 18 апреля считается superseded, а не смешивается с новой реализацией.