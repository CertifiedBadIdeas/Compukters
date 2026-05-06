# Numeric Glyph Display API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a numeric 5x7 monochrome display blit API and migrate ROM terminal glyph rendering away from 35-character string masks.

**Architecture:** Keep the existing `display::blitMono(...)` string-mask API for compatibility. Add `display::blitMono5x7(...)` as a fixed-size numeric row-mask API, route it through the runtime/core display stack, then update `terminal.ck` to store glyphs as seven `Int` rows and call the numeric API.

**Tech Stack:** Kotlin, CKL, Gradle Kotlin DSL, JUnit/kotlin.test, core display runtime.

---

## File Structure

- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`: add low-level integer-row pixel drawing.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`: expose synchronized `blitMono5x7(...)` and mark a 5x7 dirty rect.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`: record metrics and forward numeric glyph blits.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`: implement the runtime display API method.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`: add `blitMono5x7(...)` to `DeviceDisplayApi` and `NoopDeviceDisplayApi`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`: dispatch `display::blitMono5x7`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`: register the CKL builtin signature.
- Modify `docs/LANGUAGE.md`: document the new display builtin.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`: replace string glyph masks with `Glyph5x7` numeric rows.
- Modify tests under `modules/core/src/test`, `modules/compiler/src/test`, and `modules/v1_21_1/v1_21_1-neoforge/src/test`.

## Task 1: Core display support

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`

- [ ] **Step 1: Write failing display-state tests**

Add these tests to `DisplayStateTest` after `blitMonoSupportsTransparentBackground`:

```kotlin
    @Test
    fun blitMono5x7DrawsForegroundAndBackground() {
        val state = DisplayState(displayId = 5, width = 8, height = 9, pixelFormat = DisplayPixelFormat.RGB565)

        state.blitMono5x7(
            x = 1,
            y = 1,
            row0 = 0b01110,
            row1 = 0b10001,
            row2 = 0b10001,
            row3 = 0b11111,
            row4 = 0b10001,
            row5 = 0b10001,
            row6 = 0b10001,
            foreground = 0x07E0,
            background = 0x0000,
        )
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "numeric glyph blit should write foreground pixels")
        assertTrue(payload.containsRgb565(0x0000), "numeric glyph blit should write background pixels")
    }

    @Test
    fun blitMono5x7SupportsTransparentBackground() {
        val state = DisplayState(displayId = 6, width = 8, height = 9, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 9, rgb565 = 0x001F)
        state.present()

        state.blitMono5x7(
            x = 1,
            y = 1,
            row0 = 0b10000,
            row1 = 0,
            row2 = 0,
            row3 = 0,
            row4 = 0,
            row5 = 0,
            row6 = 0,
            foreground = 0x07E0,
            background = -1,
        )
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "foreground should be drawn")
        assertTrue(payload.containsRgb565(0x001F), "transparent zeros should preserve old pixels")
    }
```

- [ ] **Step 2: Write failing profiling test update**

In `DisplayProfilingTest.displayRegistryRecordsOperationsAndDrainedFrames`, add this call immediately after the existing `registry.blitMono(...)` call:

```kotlin
        registry.blitMono5x7(
            displayId = 7,
            x = 2,
            y = 2,
            row0 = 0b01110,
            row1 = 0b10001,
            row2 = 0b10001,
            row3 = 0b11111,
            row4 = 0b10001,
            row5 = 0b10001,
            row6 = 0b10001,
            foreground = 0x07E0,
            background = -1,
        )
```

Change the assertions in the same test:

```kotlin
        assertEquals(2, snapshot.operations.blitMonoCalls)
        assertEquals(41, snapshot.operations.blitMonoArea)
```

- [ ] **Step 3: Run core tests to verify RED**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest`

Expected: FAIL because `blitMono5x7` is unresolved.

- [ ] **Step 4: Implement `PixelBuffer.blitMono5x7`**

Add this method to `PixelBuffer` after `blitMono(...)`:

```kotlin
    fun blitMono5x7(
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        val rows = intArrayOf(row0, row1, row2, row3, row4, row5, row6)
        for (row in 0 until 7) {
            val targetY = y + row
            if (targetY !in 0 until height) continue
            val bits = rows[row] and 0b11111
            for (col in 0 until 5) {
                val targetX = x + col
                if (targetX !in 0 until width) continue
                val isForeground = bits and (1 shl (4 - col)) != 0
                if (isForeground) {
                    pixels[targetY * width + targetX] = foreground.toShort()
                } else if (background >= 0) {
                    pixels[targetY * width + targetX] = background.toShort()
                }
            }
        }
    }
```

- [ ] **Step 5: Implement `DisplayState.blitMono5x7`**

Add this method to `DisplayState` after `blitMono(...)`:

```kotlin
    @Synchronized
    fun blitMono5x7(
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        back.blitMono5x7(x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
        dirty.markRectDirty(x, y, 5, 7)
    }
```

- [ ] **Step 6: Implement `DisplayRegistry.blitMono5x7`**

Add this method to `DisplayRegistry` after `blitMono(...)`:

```kotlin
    fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        metricsCollector.recordBlitMono(displayId, 5, 7)
        displays[displayId]?.blitMono5x7(x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
    }
```

- [ ] **Step 7: Run core tests to verify GREEN**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest`

Expected: PASS.

- [ ] **Step 8: Commit core display support**

Run: `git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt && git commit -m "feat: add numeric 5x7 display blit"`

Expected: commit succeeds.

## Task 2: Runtime display API bridge

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`

- [ ] **Step 1: Write failing VM display API test update**

In `VmDisplayApiTest.exposesPrimaryDisplaySizeAndPublishesFrame`, fix the indentation around the existing display calls and add `api.blitMono5x7(...)` after `api.blitMono(...)`:

```kotlin
        api.clear(3, 0x0000)
        api.fillRect(3, 4, 5, 6, 7, 0xF800)
        api.blitMono(3, 1, 1, 3, 2, "101010", 0x07E0, 0x0000)
        api.blitMono5x7(3, 2, 2, 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001, 0x07E0, -1)
        api.copyRect(3, 1, 1, 3, 2, 8, 8)
        api.present(3)
```

- [ ] **Step 2: Run VM display API test to verify RED**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest`

Expected: FAIL because `blitMono5x7` is unresolved on `VmDisplayApi` or `DeviceDisplayApi`.

- [ ] **Step 3: Add `DeviceDisplayApi.blitMono5x7`**

In `DeviceRuntime.kt`, add this method to `DeviceDisplayApi` after `blitMono(...)`:

```kotlin
    fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    )
```

Add this override to `NoopDeviceDisplayApi` after its `blitMono(...)` override:

```kotlin
    override fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) = Unit
```

- [ ] **Step 4: Implement `VmDisplayApi.blitMono5x7`**

Add this override to `VmDisplayApi` after `blitMono(...)`:

```kotlin
    override fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        registry.blitMono5x7(displayId, x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
    }
```

- [ ] **Step 5: Dispatch `blitMono5x7` in `RuntimeHostBridge`**

Add this branch after the existing `"blitMono" ->` branch:

```kotlin
            "blitMono5x7" -> {
                runtime.display.blitMono5x7(
                    arguments[0].asInt(),
                    arguments[1].asInt(),
                    arguments[2].asInt(),
                    arguments[3].asInt(),
                    arguments[4].asInt(),
                    arguments[5].asInt(),
                    arguments[6].asInt(),
                    arguments[7].asInt(),
                    arguments[8].asInt(),
                    arguments[9].asInt(),
                    arguments[10].asInt(),
                    arguments[11].asInt(),
                )
                VmValue.UnitValue
            }
```

- [ ] **Step 6: Run VM display API test to verify GREEN**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApiTest`

Expected: PASS.

- [ ] **Step 7: Commit runtime display API bridge**

Run: `git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt && git commit -m "feat: bridge numeric display glyph blit"`

Expected: commit succeeds.

## Task 3: CKL builtin and language docs

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Write failing frontend test**

Add this test to `LanguageFrontendTest` after `parsesScopeCallToBuiltin`:

```kotlin
    @Test
    fun parsesNumericGlyphDisplayBuiltin() {
        val artifact =
            frontend.compile(
                "glyph.ck",
                """
                pub fun main() {
                    display::blitMono5x7(1, 2, 3, 14, 17, 17, 31, 17, 17, 17, 2016, -1)
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }
```

- [ ] **Step 2: Run frontend test to verify RED**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesNumericGlyphDisplayBuiltin`

Expected: FAIL because `blitMono5x7` is not registered in the `display` builtin module.

- [ ] **Step 3: Register `display::blitMono5x7`**

In `LanguageBuiltins.kt`, add this `BuiltinFunction` immediately after the existing `blitMono` registration:

```kotlin
                                BuiltinFunction(
                                    "blitMono5x7",
                                    listOf("Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int", "Int"),
                                    "Unit",
                                    "Draws a fixed 5x7 monochrome bitmap from seven numeric row masks.",
                                ),
```

- [ ] **Step 4: Document the display builtin**

In `docs/LANGUAGE.md`, add this bullet after the existing `blitMono(...)` bullet:

```markdown
- `blitMono5x7(displayId: Int, x: Int, y: Int, row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int, foreground: Int, background: Int): Unit`
```

Replace the paragraph that starts with `` `copyRect` copies pixels`` with:

```markdown
`copyRect` copies pixels inside the display back buffer and is useful for scrolling or moving rectangular regions. `blitMono` draws a row-major `0`/`1` monochrome mask; `1` writes the foreground color and `0` writes the background color, or remains transparent when `background < 0`. `blitMono5x7` draws a fixed 5x7 monochrome bitmap from seven numeric row masks; each row uses the low five bits from left to right (`14` is `01110`, `17` is `10001`, `31` is `11111`).
```

- [ ] **Step 5: Run compiler tests to verify GREEN**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesNumericGlyphDisplayBuiltin`

Expected: PASS.

- [ ] **Step 6: Commit builtin and docs**

Run: `git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt docs/LANGUAGE.md && git commit -m "feat: expose numeric glyph display builtin"`

Expected: commit succeeds.

## Task 4: ROM terminal glyph migration

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`

- [ ] **Step 1: Write failing ROM source regression**

Add this test to `RomScriptCompileTest` after `bundledRomTerminalHasSymmetricAngleGlyphs`:

```kotlin
    @Test
    fun bundledRomTerminalUsesNumericGlyphRows() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(source.contains("pub struct Glyph5x7"), "terminal.ck should define a numeric 5x7 glyph row struct")
        assertTrue(source.contains("fun glyphRows(ch: String): Glyph5x7"), "terminal.ck should map characters to numeric glyph rows")
        assertTrue(source.contains("display::blitMono5x7"), "terminal.ck should render glyphs through the numeric display API")
        assertFalse(source.contains("fun glyphPattern(ch: String): String"), "terminal.ck should not keep string glyph masks")
        assertFalse(Regex("return \\\"[01]{35}\\\"").containsMatchIn(source), "terminal.ck should not return 35-character string glyph masks")
        assertTrue(
            source.contains("if (ch == \">\") { return Glyph5x7(row0 = 16, row1 = 8, row2 = 4, row3 = 2, row4 = 4, row5 = 8, row6 = 16) }"),
            "terminal.ck should preserve the balanced '>' glyph as numeric rows",
        )
        assertTrue(
            source.contains("if (ch == \"<\") { return Glyph5x7(row0 = 1, row1 = 2, row2 = 4, row3 = 8, row4 = 4, row5 = 2, row6 = 1) }"),
            "terminal.ck should preserve the balanced '<' glyph as numeric rows",
        )
    }
```

Update `bundledRomTerminalHasSymmetricAngleGlyphs` to assert the same numeric `Glyph5x7(...)` rows instead of string masks.

- [ ] **Step 2: Run ROM regression to verify RED**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomTerminalUsesNumericGlyphRows`

Expected: FAIL because `terminal.ck` still uses string glyph masks.

- [ ] **Step 3: Add `Glyph5x7` and `glyphRows` in `terminal.ck`**

At the top of `terminal.ck`, keep `TerminalBuffer` and add:

```ck
pub struct Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }
```

Replace `fun glyphPattern(ch: String): String` with `fun glyphRows(ch: String): Glyph5x7`.

Convert each existing 35-character mask by splitting it into seven 5-character rows, then converting each row from binary to decimal. For example:

```ck
fun glyphRows(ch: String): Glyph5x7 {
    if (ch == "A" || ch == "a") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 31, row4 = 17, row5 = 17, row6 = 17) }
    if (ch == ">") { return Glyph5x7(row0 = 16, row1 = 8, row2 = 4, row3 = 2, row4 = 4, row5 = 8, row6 = 16) }
    if (ch == "<") { return Glyph5x7(row0 = 1, row1 = 2, row2 = 4, row3 = 8, row4 = 4, row5 = 2, row6 = 1) }
    return Glyph5x7(row0 = 31, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 17, row6 = 31)
}
```

Convert every existing branch, not only the sample branches above.

- [ ] **Step 4: Update `drawGlyph` and row render sites**

Replace the `display::blitMono(...)` call in `drawGlyph(...)` with:

```ck
    val glyph: Glyph5x7 = glyphRows(ch)
    display::blitMono5x7(displayId, x, y, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, color, -1)
```

Replace the `display::blitMono(...)` call in `renderTextRow(...)` with:

```ck
            val glyph: Glyph5x7 = glyphRows(ch)
            display::blitMono5x7(displayId, col * 6, row * 9, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, 2016, -1)
```

- [ ] **Step 5: Run ROM tests to verify GREEN**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: PASS, including `everyRomScriptCompilesCleanly()`.

- [ ] **Step 6: Commit ROM terminal migration**

Run: `git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt && git commit -m "feat: render terminal glyphs from numeric rows"`

Expected: commit succeeds.

## Task 5: Full verification

**Files:**
- No source files should change in this task unless a previous test failure identifies a concrete issue.

- [ ] **Step 1: Run compiler tests**

Run: `./gradlew :compiler:test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run ROM script tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Check diff hygiene**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 5: Inspect final status**

Run: `git status --branch --short && git log --oneline --decorate --max-count=8`

Expected: working tree is clean; recent commits include the numeric glyph API commits.