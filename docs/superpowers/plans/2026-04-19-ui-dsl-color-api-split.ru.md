# План реализации разделения color API для UI DSL

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Разделить цвет текста и цвет фоновой заливки в UI DSL, сохранив работоспособность legacy `Modifier.color(...)` во время миграции.

**Architecture:** Изменение остаётся локальным в `core.ui.foundation` и `core.ui.program`. Мы добавляем явные modifier fields и methods для текста и фона, а затем учим `ScreenProgramCompiler` lower-ить их через разные semantic paths с legacy fallback для существующих вызовов.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, `kotlin.test`, `:core:test`, существующие `ScreenProgramCompiler` и `ScreenRuntimeExecutor`.

---

## Scope Check

Этот план покрывает один сфокусированный subsystem:

- явные color channels в `UiModifier`
- разделение compiler lowering для текста и fill
- regression coverage для нового API и legacy fallback

Он не мигрирует все существующие screens на новый API и не удаляет legacy `color(...)`.

## Структура файлов

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Modify | Добавить отдельные fields и DSL methods для text/background color |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Modify | Разделить lowering text color и background fill |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt` | Modify | Добавить focused regression tests для нового API и legacy compatibility |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Verify only | Подтвердить, что broad compiler contracts остаются зелёными |

### Task 1: Разделить modifier API на text и background color channels

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Сначала написать падающие tests для нового явного API**

Обнови `ScreenProgramCompilerColorTest.kt`, чтобы он содержал такие cases:

```kotlin
package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class ScreenProgramCompilerColorTest {
    @Test
    fun backgroundColorCompilesBoxToFillRectWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                box(modifier = Modifier.size(40, 20).backgroundColor(Color.Red)) { }
            },
        )

        assertEquals(
            listOf(RenderOp.FillRect("root-0", Color.Red)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun textColorCompilesTextToDrawTextWithItsOwnColor() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                text(
                    value = textExpr { "Hello" },
                    modifier = Modifier.textColor(Color.Green),
                )
            },
        )

        assertEquals(
            listOf(RenderOp.DrawText("root-0", "Hello", Color.Green)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun legacyColorStillCompilesBoxToFillRect() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                box(modifier = Modifier.size(40, 20).color(Color.Red)) { }
            },
        )

        assertEquals(
            listOf(RenderOp.FillRect("root-0", Color.Red)),
            program.renderProgram.staticOps,
        )
    }

    @Test
    fun legacyColorStillCompilesTextToDrawText() {
        val compiler = ScreenProgramCompiler()

        val program = compiler.compile(
            ui {
                text(
                    value = textExpr { "Hello" },
                    modifier = Modifier.color(Color.Blue),
                )
            },
        )

        assertEquals(
            listOf(RenderOp.DrawText("root-0", "Hello", Color.Blue)),
            program.renderProgram.staticOps,
        )
    }
}
```

- [ ] **Step 2: Запустить focused color test и убедиться, что нового API ещё нет**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: FAIL с unresolved references на `backgroundColor` и `textColor`.

- [ ] **Step 3: Добавить отдельные modifier fields и methods**

Обнови `UiModifier.kt`, чтобы состояние было разделено:

```kotlin
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
    val color: Color? = null,
    val textColor: Color? = null,
    val backgroundColor: Color? = null,
) {
    fun color(value: Color): UiModifier = copy(color = value)

    fun textColor(value: Color): UiModifier = copy(textColor = value)

    fun backgroundColor(value: Color): UiModifier = copy(backgroundColor = value)
}
```

Не удаляй `color(...)` в рамках этой task.

- [ ] **Step 4: Снова прогнать focused color test**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: FAIL теперь должен перейти от missing API к неверному compiler behavior.

### Task 2: Разделить compiler lowering для text и fill semantics

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Обновить compiler, чтобы он резолвил background color для box**

Внутри `ScreenProgramCompiler` добавь helper resolution такого вида:

```kotlin
private fun resolveBackgroundColor(element: UiElement.Box): Color? =
    element.modifier.backgroundColor ?: element.modifier.color

private fun resolveTextColor(element: UiElement.Text): Color =
    element.modifier.textColor ?: element.modifier.color ?: Color.Transparent
```

Используй их в lowering:

```kotlin
is UiElement.Box -> {
    val backgroundColor = resolveBackgroundColor(element)
    if (
        element.modifier.role == UiRole.Button ||
        backgroundColor != null
    ) {
        renderOps += RenderOp.FillRect(nodeId, backgroundColor ?: Color.Transparent)
    }
    addInteraction(nodeId, element, hitRegions, inputRoutes, focusTargets)
    ...
}

is UiElement.Text -> {
    renderOps += RenderOp.DrawText(nodeId, element.value.evaluate(), resolveTextColor(element))
}
```

- [ ] **Step 2: Прогнать focused color tests и убедиться, что они зелёные**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: PASS.

- [ ] **Step 3: Прогнать более широкий compiler verification**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" --console=plain`

Expected: PASS.

### Task 3: Финальный verification pass для slice

**Files:**
- Verify only: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Verify only: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Verify only: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Прогнать весь focused verification set**

Run:

```bash
./gradlew :core:test \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" \
  --console=plain
```

Expected: PASS.

- [ ] **Step 2: Подтвердить итоговую public API shape**

Вручную проверь, что эти примеры валидны после реализации:

```kotlin
text(value = textExpr { "Status" }, modifier = Modifier.textColor(Color.White))

box(modifier = Modifier.size(120, 30).backgroundColor(Color.Blue)) { }

box(modifier = Modifier.size(120, 30).color(Color.Red)) { }
```

Expected:

- первый пример влияет только на текст
- второй пример влияет только на fill
- третий пример продолжает работать как legacy fallback

## Self-Review

- Spec coverage: план покрывает явные text/background APIs, compiler fallback и focused tests для нового и legacy поведения.
- Placeholder scan: нет `TODO`, `TBD` или размытых шагов вида “добавить тесты позже”.
- Type consistency: `textColor`, `backgroundColor` и legacy `color` используются последовательно между foundation и compiler tasks.
- Execution consistency: все указанные файлы уже существуют, а verification commands нацелены на тесты, которые уже есть в этом worktree.