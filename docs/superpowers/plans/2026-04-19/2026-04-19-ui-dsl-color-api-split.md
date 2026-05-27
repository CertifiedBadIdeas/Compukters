# UI DSL Color API Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split text color from background fill color in the UI DSL while keeping legacy `Modifier.color(...)` behavior working during migration.

**Architecture:** Keep the change local to `core.ui.foundation` and `core.ui.program`. Add explicit modifier fields and methods for text and background colors, then make `ScreenProgramCompiler` resolve them through separate semantic paths with legacy fallback for existing call sites.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, `kotlin.test`, `:core:test`, existing `ScreenProgramCompiler` and `ScreenRuntimeExecutor`.

---

## Scope Check

This plan covers one focused subsystem:

- explicit color channels in `UiModifier`
- compiler lowering split for text vs. fill
- regression coverage for new API and legacy fallback

It does not migrate all existing screens to the new API and does not remove legacy `color(...)`.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt` | Modify | Add separate text/background color fields and DSL methods |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt` | Modify | Resolve text and background colors through separate lowering paths |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt` | Modify | Add focused regression tests for new API and legacy compatibility |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerTest.kt` | Verify only | Ensure broad compiler contracts remain green |

### Task 1: Split the modifier API into text and background color channels

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Write failing tests for the new explicit API**

Update `ScreenProgramCompilerColorTest.kt` to contain these cases:

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

- [ ] **Step 2: Run the focused color test to verify the new API is missing**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: FAIL with unresolved references for `backgroundColor` and `textColor`.

- [ ] **Step 3: Add separate modifier fields and methods**

Update `UiModifier.kt` to split the state:

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

Do not remove `color(...)` in this task.

- [ ] **Step 4: Run the focused color test again**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: FAIL now moves from missing API to wrong compiler behavior.

### Task 2: Split compiler lowering for text and fill semantics

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Update the compiler to resolve background color for boxes**

Inside `ScreenProgramCompiler`, add helper resolution like this:

```kotlin
private fun resolveBackgroundColor(element: UiElement.Box): Color? =
    element.modifier.backgroundColor ?: element.modifier.color

private fun resolveTextColor(element: UiElement.Text): Color =
    element.modifier.textColor ?: element.modifier.color ?: Color.Transparent
```

Use them in lowering:

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

- [ ] **Step 2: Run the focused color tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" --console=plain`

Expected: PASS.

- [ ] **Step 3: Run broader compiler verification**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" --console=plain`

Expected: PASS.

### Task 3: Final verification for the slice

**Files:**
- Verify only: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation/UiModifier.kt`
- Verify only: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompiler.kt`
- Verify only: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/ui/program/ScreenProgramCompilerColorTest.kt`

- [ ] **Step 1: Run the full focused verification set**

Run:

```bash
./gradlew :core:test \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerColorTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompilerTest" \
  --tests "ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutorTest" \
  --console=plain
```

Expected: PASS.

- [ ] **Step 2: Confirm the public API shape**

Manually verify these examples are valid after the implementation:

```kotlin
text(value = textExpr { "Status" }, modifier = Modifier.textColor(Color.White))

box(modifier = Modifier.size(120, 30).backgroundColor(Color.Blue)) { }

box(modifier = Modifier.size(120, 30).color(Color.Red)) { }
```

Expected:

- first example affects text only
- second example affects fill only
- third example still works as legacy fallback

## Self-Review

- Spec coverage: the plan covers explicit text/background APIs, compiler fallback, and focused tests for both new and legacy behavior.
- Placeholder scan: no `TODO`, `TBD`, or vague “add tests later” steps remain.
- Type consistency: `textColor`, `backgroundColor`, and legacy `color` are used consistently between the foundation and compiler tasks.
- Execution consistency: all referenced files already exist, and verification commands target tests that are already present in this worktree.