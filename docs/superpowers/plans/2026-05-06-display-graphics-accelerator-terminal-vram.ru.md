# План реализации display graphics accelerator и terminal VRAM

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить generic accelerated framebuffer primitives и переписать ROM terminal renderer вокруг dirty text video memory вместо per-pixel/full-screen redraw.

**Architecture:** Расширяем существующий framebuffer display stack операциями `copyRect` и `blitMono`, exposing them как CKL `display::*` APIs и tracking them в profiling metrics. Затем bundled ROM terminal остаётся ROM-программой, но выражает graphics work через generic accelerated framebuffer operations.

**Tech Stack:** Kotlin, Gradle, kotlin.test/JUnit 5, CKL ROM resources, существующие `DisplayRegistry`/`PixelBuffer`/`RuntimeHostBridge`, существующие display/runtime profiling tests.

---

## File structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt` — `copyRect` and `blitMono` in `DeviceDisplayApi` and no-op implementation.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — expose new display builtins.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — dispatch new builtins.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt` — delegate new operations to registry.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt` — implement clipped `copyRect` and `blitMono`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt` — apply operations and mark dirty rectangles.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt` — expose operations and record metrics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt` — add `copyRect`/`blitMono` counters.
- Modify display/core tests under `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/**`.
- Modify `docs/LANGUAGE.md` and `docs/ARCHITECTURE.md`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck` — dirty text VRAM + `copyRect`/`blitMono`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck` — firmware glyphs via `blitMono`.
- Modify `RuntimeDisplayProfilingTest.kt` and `BackgroundDeviceVmTest.kt` — metrics and behavior coverage.

---

### Task 1: Add core framebuffer operations

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`

- [ ] **Step 1: Write failing `copyRect` and `blitMono` tests**

Append tests to `DisplayStateTest` equivalent to the English plan:

```kotlin
@Test
fun copyRectCopiesPixelsAndMarksDestinationDirty() { /* create DisplayState, draw red 2x2 block, present, copyRect to a new location, assert emitted payload contains 0xF800 */ }

@Test
fun blitMonoDrawsForegroundAndBackground() { /* call blitMono with mask "101010", assert payload contains foreground 0x07E0 and background 0x0000 */ }

@Test
fun blitMonoSupportsTransparentBackground() { /* fill blue background, present, blitMono with background=-1, assert foreground and preserved background exist */ }
```

Use the helper from the English plan:

```kotlin
private fun ByteArray.containsRgb565(rgb565: Int): Boolean {
    var i = 0
    val hi = (rgb565 ushr 8).toByte()
    val lo = rgb565.toByte()
    while (i + 1 < size) {
        if (this[i] == hi && this[i + 1] == lo) return true
        i += 2
    }
    return false
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest
```

Expected: FAIL because `DisplayState.copyRect` and `DisplayState.blitMono` do not exist.

- [ ] **Step 3: Implement `PixelBuffer.copyRect` and `PixelBuffer.blitMono`**

Add clipped native Kotlin loops from the English plan. `copyRect` must use a temporary `ShortArray` so overlapping source/destination copies are correct. `blitMono` must treat missing mask bits as `0`, write foreground for `1`, write background for `0` when `background >= 0`, and leave old pixels when `background < 0`.

- [ ] **Step 4: Add `DisplayState` methods**

Add synchronized `copyRect(...)` and `blitMono(...)` to `DisplayState`, delegate to `back`, then call `dirty.markRectDirty(...)` on the affected destination rectangle.

- [ ] **Step 5: Run GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt
git commit -m "feat: add framebuffer copy and mono blit"
```

---

### Task 2: Expose display API and profiling counters

**Files:** same as English Task 2.

- [ ] **Step 1: Add failing API and profiling tests**

Update `VmDisplayApiTest` to call:

```kotlin
api.blitMono(3, 1, 1, 3, 2, "101010", 0x07E0, 0x0000)
api.copyRect(3, 1, 1, 3, 2, 8, 8)
```

Update `DisplayProfilingTest` to call:

```kotlin
collector.recordCopyRect(displayId = 1, width = 4, height = 5)
collector.recordBlitMono(displayId = 1, width = 6, height = 7)
```

Assert exact counters: `copyRectCalls=1`, `copyRectArea=20`, `blitMonoCalls=1`, `blitMonoArea=42`. Also add registry calls/assertions for `copyRectArea=6` and `blitMonoArea=6`.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: FAIL because methods/counters do not exist.

- [ ] **Step 3: Add `DeviceDisplayApi` methods**

Add `copyRect(...)` and `blitMono(...)` signatures to `DeviceDisplayApi` and no-op overrides to `NoopDeviceDisplayApi` using the exact signatures from the English plan.

- [ ] **Step 4: Add compiler builtins and host dispatch**

Add `BuiltinFunction("copyRect", listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int"), "Unit", ...)` and `BuiltinFunction("blitMono", listOf("Int", "Int", "Int", "Int", "Int", "String", "Int", "Int"), "Unit", ...)` to display module. Add `RuntimeHostBridge.invokeDisplay` cases that call `runtime.display.copyRect(...)` and `runtime.display.blitMono(...)`.

- [ ] **Step 5: Add core delegation and metrics**

Add methods to `VmDisplayApi`, `DisplayRegistry`, and `DisplayProfiling`. `DisplayOperationMetrics` must include `copyRectCalls`, `copyRectArea`, `blitMonoCalls`, and `blitMonoArea`; `RecordingDisplayMetricsCollector` must use atomic counters and the same non-negative area rule as `fillRect`.

- [ ] **Step 6: Run GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: PASS.

- [ ] **Step 7: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit Task 2**

Run the `git add ...` and commit command from English Task 2 with message `feat: expose accelerated display primitives`.

---

### Task 3: Update firmware and terminal glyph rendering to `blitMono`

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Add failing profiling expectations**

In `RuntimeDisplayProfilingTest`, assert:

```kotlin
assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
assertTrue(displaySnapshot.operations.fillRectCalls < 1000, displaySnapshot.summary())
```

Run the profiling test and expect failure before ROM uses `display::blitMono`.

- [ ] **Step 2: Replace terminal glyph pixel loop with `blitMono`**

In `rom/terminal.ck`, replace `drawGlyph` with:

```ck
fun drawGlyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    val x: Int = column * 6
    val y: Int = row * 9
    if (ch == " ") {
        display::fillRect(displayId, x, y, 6, 9, 0)
        return
    }
    display::blitMono(displayId, x, y, 5, 7, glyphPattern(ch), color, -1)
}
```

In `firmware/bios.ck`, replace per-pixel glyph drawing with `display::blitMono(displayId, x, y, 5, 7, glyphPattern(ch), color, -1)` while preserving existing call sites.

- [ ] **Step 3: Run profiling test GREEN**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: PASS, `blitMonoCalls > 0`, `fillRectCalls < 1000`.

- [ ] **Step 4: Run existing terminal behavior tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Task 3**

Commit with message `feat: render bundled glyphs with mono blits`.

---

### Task 4: Add ROM terminal dirty text rows and scroll copy

**Files:** same as English Task 4.

- [ ] **Step 1: Add scroll-heavy profiling regression**

Add a scroll-heavy test to `RuntimeDisplayProfilingTest` that boots terminal, produces enough output to exceed visible rows, drains frames, and asserts:

```kotlin
assertTrue(displaySnapshot.operations.copyRectCalls > 0, displaySnapshot.summary())
assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: FAIL because terminal does not call `copyRect` yet.

- [ ] **Step 3: Refactor terminal rendering state**

In `rom/terminal.ck`, keep glyph patterns and add helpers from English Task 4: `cellCount`, `blankCells`, `setCell`, and `cellAt`. Maintain state: `cells`, `cursorRow`, `cursorColumn`, and `line`.

- [ ] **Step 4: Add row rendering helpers**

Add `clearTextRow(displayId, row)` and `renderTextRow(displayId, cells, row)`. `renderTextRow` clears a text row once, then draws non-space glyphs with `display::blitMono`.

- [ ] **Step 5: Add scroll helper**

Add `scrollUp(displayId, cells)` exactly as in English Task 4. It must call:

```ck
display::copyRect(displayId, 0, 9, cols * 6, (rs - 1) * 9, 0, 0)
```

Then clear the last row and shift cell text up by one row.

- [ ] **Step 6: Replace full render on output/input**

Replace full-screen output redraw with character-by-character cell updates, dirty row rendering, and one `display::present(displayId)` after the batch. Input/backspace must update only the current input row and preserve prompt cells. Display attach/resize may rebuild cells and redraw all rows once.

- [ ] **Step 7: Run terminal tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: BUILD SUCCESSFUL, `copyRectCalls > 0` in scroll-heavy profiling test.

- [ ] **Step 8: Commit Task 4**

Commit with message `feat: use terminal text vram dirty rows`.

---

### Task 5: Document API and verify

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Document CKL display API**

In `docs/LANGUAGE.md`, add display entries for `copyRect(...)` and `blitMono(...)`, plus the explanatory note from English Task 5.

- [ ] **Step 2: Document architecture**

In `docs/ARCHITECTURE.md`, add the accelerated framebuffer primitive note from English Task 5.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run forbidden API audit**

Run:

```bash
rg 'terminal::|stdout::|DeviceTerminalApi|DeviceStdioApi|VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink' modules/compiler/src/main modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources
```

Expected: no output, exit code 1.

- [ ] **Step 5: Commit Task 5**

Run:

```bash
git add docs/LANGUAGE.md docs/ARCHITECTURE.md
git commit -m "docs: document accelerated display primitives"
```

---

## Final verification checklist

- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest` passes.
- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest` passes.
- [ ] `./gradlew :compiler:test` passes.
- [ ] `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest` passes.
- [ ] `./gradlew test` passes.
- [ ] Profiling output shows `blitMonoCalls > 0` and `fillRectCalls < 1000` for bundled terminal workload.
- [ ] Scroll-heavy profiling output shows `copyRectCalls > 0`.
- [ ] Forbidden terminal/stdout audit has no production/resource matches.
- [ ] Final implementation adds generic display primitives and does not add text-specific VM rendering APIs.
