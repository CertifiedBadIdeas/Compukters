# Display Graphics Accelerator and Terminal VRAM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add generic accelerated framebuffer primitives and rewrite the ROM terminal renderer around dirty text video memory instead of per-pixel/full-screen redraws.

**Architecture:** Extend the existing framebuffer display stack with `copyRect` and `blitMono`, expose them as CKL `display::*` APIs, and track them in profiling metrics. Then update bundled ROM terminal rendering so terminal semantics stay in ROM while graphics work is expressed as generic accelerated framebuffer operations.

**Tech Stack:** Kotlin, Gradle, kotlin.test/JUnit 5, CKL ROM resources, existing `DisplayRegistry`/`PixelBuffer`/`RuntimeHostBridge` display stack, existing display/runtime profiling tests.

---

## File structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt` — add `copyRect` and `blitMono` to `DeviceDisplayApi` and no-op implementation.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — expose the new display builtins.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — dispatch the new builtins.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt` — delegate new operations to the registry.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt` — implement clipped `copyRect` and `blitMono`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt` — apply operations and mark dirty rectangles.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt` — expose operations and record metrics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt` — add `copyRect`/`blitMono` counters.
- Modify display/core tests under `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/**`.
- Verify compiler builtin registration through `./gradlew :compiler:test`; this plan does not create a new compiler test file unless execution reveals an existing matching test file for display builtins.
- Modify `docs/LANGUAGE.md` — document new display API.
- Modify `docs/ARCHITECTURE.md` — document accelerated framebuffer primitives.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck` — use dirty text VRAM + `copyRect`/`blitMono`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck` — use `blitMono` for firmware status glyphs.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt` — assert improved metrics.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt` — preserve terminal behavior regressions and add scroll-heavy coverage.

---

### Task 1: Add core framebuffer operations

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`

- [ ] **Step 1: Write failing `copyRect` and `blitMono` tests**

Append these tests to `DisplayStateTest`:

```kotlin
    @Test
    fun copyRectCopiesPixelsAndMarksDestinationDirty() {
        val state = DisplayState(displayId = 2, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 4, rgb565 = 0x0000)
        state.fillRect(x = 0, y = 0, width = 2, height = 2, rgb565 = 0xF800)
        state.present()

        state.copyRect(srcX = 0, srcY = 0, width = 2, height = 2, dstX = 3, dstY = 1)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0xF800), "copyRect should copy red pixels into emitted tiles")
        assertFalse(frame.fullRefresh)
    }

    @Test
    fun blitMonoDrawsForegroundAndBackground() {
        val state = DisplayState(displayId = 3, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.blitMono(x = 1, y = 1, width = 3, height = 2, mask = "101010", foreground = 0x07E0, background = 0x0000)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "blitMono should write foreground pixels")
        assertTrue(payload.containsRgb565(0x0000), "blitMono should write background pixels")
    }

    @Test
    fun blitMonoSupportsTransparentBackground() {
        val state = DisplayState(displayId = 4, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 4, rgb565 = 0x001F)
        state.present()

        state.blitMono(x = 1, y = 1, width = 3, height = 2, mask = "100000", foreground = 0x07E0, background = -1)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "foreground should be drawn")
        assertTrue(payload.containsRgb565(0x001F), "transparent zeros should preserve old pixels")
    }

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

Add methods to `PixelBuffer`:

```kotlin
    fun copyRect(
        srcX: Int,
        srcY: Int,
        rectWidth: Int,
        rectHeight: Int,
        dstX: Int,
        dstY: Int,
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return
        val minSrcX = srcX.coerceAtLeast(0)
        val minSrcY = srcY.coerceAtLeast(0)
        val maxSrcX = (srcX + rectWidth).coerceAtMost(width)
        val maxSrcY = (srcY + rectHeight).coerceAtMost(height)
        val copyWidth = (maxSrcX - minSrcX).coerceAtMost(width - dstX.coerceAtLeast(0))
        val copyHeight = (maxSrcY - minSrcY).coerceAtMost(height - dstY.coerceAtLeast(0))
        if (copyWidth <= 0 || copyHeight <= 0) return

        val adjustedDstX = dstX + (minSrcX - srcX)
        val adjustedDstY = dstY + (minSrcY - srcY)
        if (adjustedDstX !in 0 until width || adjustedDstY !in 0 until height) return

        val tmp = ShortArray(copyWidth * copyHeight)
        var offset = 0
        for (row in 0 until copyHeight) {
            val sourceBase = (minSrcY + row) * width + minSrcX
            for (col in 0 until copyWidth) {
                tmp[offset++] = pixels[sourceBase + col]
            }
        }
        offset = 0
        for (row in 0 until copyHeight) {
            val targetBase = (adjustedDstY + row) * width + adjustedDstX
            for (col in 0 until copyWidth) {
                pixels[targetBase + col] = tmp[offset++]
            }
        }
    }

    fun blitMono(
        x: Int,
        y: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return
        for (row in 0 until bitmapHeight) {
            val targetY = y + row
            if (targetY !in 0 until height) continue
            for (col in 0 until bitmapWidth) {
                val targetX = x + col
                if (targetX !in 0 until width) continue
                val maskIndex = row * bitmapWidth + col
                val bit = if (maskIndex < mask.length) mask[maskIndex] else '0'
                if (bit == '1') {
                    pixels[targetY * width + targetX] = foreground.toShort()
                } else if (background >= 0) {
                    pixels[targetY * width + targetX] = background.toShort()
                }
            }
        }
    }
```

- [ ] **Step 4: Add `DisplayState` methods**

Add to `DisplayState`:

```kotlin
    @Synchronized
    fun copyRect(
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    ) {
        back.copyRect(srcX, srcY, width, height, dstX, dstY)
        dirty.markRectDirty(dstX, dstY, width, height)
    }

    @Synchronized
    fun blitMono(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        back.blitMono(x, y, width, height, mask, foreground, background)
        dirty.markRectDirty(x, y, width, height)
    }
```

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

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`

- [ ] **Step 1: Add failing API and profiling tests**

In `VmDisplayApiTest`, after `api.fillRect(...)`, add:

```kotlin
        api.blitMono(3, 1, 1, 3, 2, "101010", 0x07E0, 0x0000)
        api.copyRect(3, 1, 1, 3, 2, 8, 8)
```

In `DisplayProfilingTest.recordingCollectorCountsOperationsAndFrames`, add:

```kotlin
        collector.recordCopyRect(displayId = 1, width = 4, height = 5)
        collector.recordBlitMono(displayId = 1, width = 6, height = 7)
```

And assert:

```kotlin
        assertEquals(1, snapshot.operations.copyRectCalls)
        assertEquals(20, snapshot.operations.copyRectArea)
        assertEquals(1, snapshot.operations.blitMonoCalls)
        assertEquals(42, snapshot.operations.blitMonoArea)
```

In `displayRegistryRecordsOperationsAndDrainedFrames`, call:

```kotlin
        registry.blitMono(displayId = 7, x = 1, y = 1, width = 3, height = 2, mask = "111000", foreground = 0x07E0, background = -1)
        registry.copyRect(displayId = 7, srcX = 1, srcY = 1, width = 3, height = 2, dstX = 5, dstY = 5)
```

And assert `copyRectCalls == 1`, `copyRectArea == 6`, `blitMonoCalls == 1`, `blitMonoArea == 6`.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: FAIL because new API/profiling methods do not exist.

- [ ] **Step 3: Add `DeviceDisplayApi` methods**

In `DeviceRuntime.kt`, add to `DeviceDisplayApi`:

```kotlin
    fun copyRect(
        displayId: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    )

    fun blitMono(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    )
```

Add no-op overrides in `NoopDeviceDisplayApi`.

- [ ] **Step 4: Add compiler builtins and host dispatch**

In `LanguageBuiltins.kt`, add functions to display module:

```kotlin
                                BuiltinFunction(
                                    "copyRect",
                                    listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Copies a rectangle inside the display back buffer.",
                                ),
                                BuiltinFunction(
                                    "blitMono",
                                    listOf("Int", "Int", "Int", "Int", "Int", "String", "Int", "Int"),
                                    "Unit",
                                    "Draws a row-major monochrome bitmap mask.",
                                ),
```

In `RuntimeHostBridge.invokeDisplay`, add cases:

```kotlin
            "copyRect" -> {
                runtime.display.copyRect(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                    arguments[6].asInt(),
                )
                VmValue.UnitValue
            }

            "blitMono" -> {
                runtime.display.blitMono(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asString(),
                    arguments[6].asInt(),
                    arguments[7].asInt(),
                )
                VmValue.UnitValue
            }
```

If `VmValue.asString()` is not accessible in this scope, use the existing string extraction helper pattern already used by filesystem/ipc functions in `RuntimeHostBridge`.

- [ ] **Step 5: Add core delegation and metrics**

Add `copyRect` and `blitMono` methods to `VmDisplayApi` and `DisplayRegistry`.

Extend `DisplayMetricsCollector` with:

```kotlin
    fun recordCopyRect(displayId: Int, width: Int, height: Int)
    fun recordBlitMono(displayId: Int, width: Int, height: Int)
```

Extend `DisplayOperationMetrics`:

```kotlin
    val copyRectCalls: Long = 0,
    val copyRectArea: Long = 0,
    val blitMonoCalls: Long = 0,
    val blitMonoArea: Long = 0,
```

Implement counters in `NoOpDisplayMetricsCollector` and `RecordingDisplayMetricsCollector` using the same area pattern as `fillRect`.

In `DisplayRegistry.copyRect`, record `recordCopyRect(displayId, width, height)` before delegating. In `DisplayRegistry.blitMono`, record `recordBlitMono(displayId, width, height)` before delegating.

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

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt
git commit -m "feat: expose accelerated display primitives"
```

---

### Task 3: Update firmware and terminal glyph rendering to `blitMono`

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Add failing profiling expectations**

In `RuntimeDisplayProfilingTest`, add assertions after `displaySnapshot`:

```kotlin
            assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
            assertTrue(displaySnapshot.operations.fillRectCalls < 1000, displaySnapshot.summary())
```

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: FAIL because `blitMonoCalls` is zero before ROM resources use `display::blitMono`.

- [ ] **Step 2: Replace terminal glyph pixel loop with `blitMono`**

In `rom/terminal.ck`, replace `drawGlyph` body with:

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

In `firmware/bios.ck`, replace the per-pixel glyph loop with a direct `display::blitMono(displayId, x, y, 5, 7, glyphPattern(ch), color, -1)` call. Preserve existing function names and call sites.

- [ ] **Step 3: Run profiling test GREEN**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: PASS, `blitMonoCalls > 0`, and `fillRectCalls < 1000` in the printed display summary.

- [ ] **Step 4: Run existing terminal behavior tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck \
    modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck \
    modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt
git commit -m "feat: render bundled glyphs with mono blits"
```

---

### Task 4: Add ROM terminal dirty text rows and scroll copy

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Add scroll-heavy profiling regression**

Add a test to `RuntimeDisplayProfilingTest` that boots the bundled terminal, writes enough shell/output text to exceed the visible rows, drains frames, and asserts:

```kotlin
assertTrue(displaySnapshot.operations.copyRectCalls > 0, displaySnapshot.summary())
assertTrue(displaySnapshot.operations.blitMonoCalls > 0, displaySnapshot.summary())
```

Use the existing boot/tick helper structure in the file. The workload should run a command or inject output through the shell path already used by bundled ROM tests; do not introduce terminal/stdout builtins.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: FAIL because terminal does not call `copyRect` yet.

- [ ] **Step 3: Refactor terminal rendering state**

In `rom/terminal.ck`, keep the existing glyph patterns and add helpers:

```ck
fun cellCount(displayId: Int): Int { return columns(displayId) * rows(displayId) }

fun blankCells(count: Int): String {
    var result: String = ""
    var i: Int = 0
    while i < count {
        result = result + " "
        i = i + 1
    }
    return result
}

fun setCell(cells: String, index: Int, ch: String): String {
    var result: String = ""
    var i: Int = 0
    while i < strings::length(cells) {
        if (i == index) { result = result + ch } else { result = result + strings::charAt(cells, i) }
        i = i + 1
    }
    return result
}

fun cellAt(cells: String, index: Int): String {
    if (index < 0 || index >= strings::length(cells)) { return " " }
    return strings::charAt(cells, index)
}
```

- [ ] **Step 4: Add row rendering helpers**

Add helpers:

```ck
fun clearTextRow(displayId: Int, row: Int) {
    display::fillRect(displayId, 0, row * 9, columns(displayId) * 6, 9, 0)
}

fun renderTextRow(displayId: Int, cells: String, row: Int) {
    clearTextRow(displayId, row)
    var col: Int = 0
    val cols: Int = columns(displayId)
    while col < cols {
        val ch: String = cellAt(cells, row * cols + col)
        if (ch != " ") {
            display::blitMono(displayId, col * 6, row * 9, 5, 7, glyphPattern(ch), 2016, -1)
        }
        col = col + 1
    }
}
```

- [ ] **Step 5: Add scroll helper**

Add:

```ck
fun scrollUp(displayId: Int, cells: String): String {
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    display::copyRect(displayId, 0, 9, cols * 6, (rs - 1) * 9, 0, 0)
    clearTextRow(displayId, rs - 1)
    var result: String = ""
    var i: Int = cols
    while i < strings::length(cells) {
        result = result + strings::charAt(cells, i)
        i = i + 1
    }
    var col: Int = 0
    while col < cols {
        result = result + " "
        col = col + 1
    }
    return result
}
```

- [ ] **Step 6: Replace full render on output/input**

Rewrite the main loop state to keep:

```ck
var cells: String = blankCells(cellCount(displayId))
var cursorRow: Int = 0
var cursorColumn: Int = 0
var line: String = ""
```

When output chunk arrives, append characters one by one into `cells`, call `scrollUp` when cursor row reaches `rows(displayId)`, render only changed rows with `renderTextRow`, and call `display::present(displayId)` after the chunk batch.

When input changes, update only cells from the current prompt/input start column to the end of the old/new input area, render the current row, and present once.

On display attach/resize, rebuild `cells = blankCells(cellCount(displayId))`, reset cursor and line, and render all rows once.

Keep behavior-visible constraints from existing tests: prompt remains visible while typing, Enter submits `line`, Backspace updates input, shell output renders green glyphs.

- [ ] **Step 7: Run terminal tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: BUILD SUCCESSFUL, `copyRectCalls > 0` in scroll-heavy profiling test, and the bundled terminal behavior tests pass.

- [ ] **Step 8: Commit Task 4**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck \
    modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt \
    modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt
git commit -m "feat: use terminal text vram dirty rows"
```

---

### Task 5: Document API and verify

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Document CKL display API**

In `docs/LANGUAGE.md`, add to the `display` list:

```markdown
- `copyRect(displayId: Int, srcX: Int, srcY: Int, width: Int, height: Int, dstX: Int, dstY: Int): Unit`
- `blitMono(displayId: Int, x: Int, y: Int, width: Int, height: Int, mask: String, foreground: Int, background: Int): Unit`
```

Add a short note below the list:

```markdown
`copyRect` copies pixels inside a display back buffer and is useful for scrolling or moving rectangular regions. `blitMono` draws a row-major `0`/`1` monochrome mask; `1` writes the foreground color and `0` writes the background color, or remains transparent when `background < 0`.
```

- [ ] **Step 2: Document architecture**

In `docs/ARCHITECTURE.md`, extend the runtime display section:

```markdown
The display device exposes generic accelerated framebuffer primitives. `copyRect` supports back-buffer region copies such as terminal scrolling and future sprite/window movement. `blitMono` supports bitmap masks such as ROM glyphs and icons. These primitives remain framebuffer operations rather than terminal-specific rendering APIs, so the ROM terminal stays a program layered on top of the display device.
```

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
- [ ] Profiling output shows `blitMonoCalls > 0` and `fillRectCalls < 1000` for the bundled terminal workload.
- [ ] Scroll-heavy profiling output shows `copyRectCalls > 0`.
- [ ] Forbidden terminal/stdout audit has no production/resource matches.
- [ ] The final implementation adds generic display primitives and does not add text-specific VM rendering APIs.
