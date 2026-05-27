# Packed Bitwise Glyphs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ROM terminal `Glyph5x7` record allocation with packed bitwise `Long` glyphs rendered through a new packed display builtin.

**Architecture:** Keep the existing core framebuffer and `display::blitMono5x7(...)` path intact. Add an additive CKL builtin, decode the packed `Long` in `RuntimeHostBridge`, and migrate only ROM terminal glyph lookup/rendering to `glyphBits(ch): Long` plus `display::blitMono5x7Packed(...)`.

**Tech Stack:** Kotlin, CKL runtime/frontend, CKL ROM scripts, Gradle, kotlin.test.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
  - Register `display::blitMono5x7Packed(Int, Int, Int, Long, Int, Int): Unit`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
  - Decode packed glyph bits and delegate to `runtime.display.blitMono5x7(...)`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
  - Add frontend compile test for the new builtin.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
  - Add runtime bridge decoding test and display capture support in `RecordingRuntime`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
  - Replace `Glyph5x7` and `glyphRows(...)` with `glyphBits(...): Long` packed literals.
  - Render through `display::blitMono5x7Packed(...)`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
  - Update ROM source regressions to require packed glyphs and reject `Glyph5x7` allocation.
- Modify `docs/LANGUAGE.md`
  - Document the packed display builtin.

## Task 1: Add Packed Display Builtin and Bridge Decode

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`

- [ ] **Step 1: Write failing frontend builtin test**

Add this test next to `parsesNumericGlyphDisplayBuiltin` in `LanguageFrontendTest`:

```kotlin
@Test
fun parsesPackedNumericGlyphDisplayBuiltin() {
    val artifact =
        frontend.compile(
            "packed_glyph.ck",
            """
            pub fun main() {
                display::blitMono5x7Packed(1, 2, 3, 0b01110100011000111111100011000110001L, 2016, -1)
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

- [ ] **Step 2: Write failing runtime bridge decode test**

In `LanguageRuntimeTest`, add a small capture model near `RecordingRuntime`:

```kotlin
internal data class CapturedMono5x7Blit(
    val displayId: Int,
    val x: Int,
    val y: Int,
    val rows: List<Int>,
    val foreground: Int,
    val background: Int,
)
```

Add this test near other runtime bridge tests:

```kotlin
@Test
fun packedGlyphDisplayBuiltinDecodesRowsThroughRuntimeBridge() {
    val artifact =
        frontend.compile(
            "packed_display.ck",
            """
            pub fun main() {
                display::blitMono5x7Packed(7, 2, 3, 0b01110100011000111111100011000110001L, 2016, -1);
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime()
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(
        listOf(
            CapturedMono5x7Blit(
                displayId = 7,
                x = 2,
                y = 3,
                rows = listOf(14, 17, 17, 31, 17, 17, 17),
                foreground = 2016,
                background = -1,
            ),
        ),
        runtime.mono5x7Blits,
    )
}
```

Extend `RecordingRuntime` with a capture list and display API override:

```kotlin
val mono5x7Blits = mutableListOf<CapturedMono5x7Blit>()

override val display: DeviceDisplayApi =
    object : DeviceDisplayApi by NoopDeviceDisplayApi {
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
            mono5x7Blits +=
                CapturedMono5x7Blit(
                    displayId = displayId,
                    x = x,
                    y = y,
                    rows = listOf(row0, row1, row2, row3, row4, row5, row6),
                    foreground = foreground,
                    background = background,
                )
        }
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesPackedNumericGlyphDisplayBuiltin --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.packedGlyphDisplayBuiltinDecodesRowsThroughRuntimeBridge
```

Expected: frontend test reports unknown `display::blitMono5x7Packed`; runtime test cannot compile cleanly until the builtin exists.

- [ ] **Step 4: Register builtin**

In `LanguageBuiltins.kt`, add this `BuiltinFunction` after `blitMono5x7`:

```kotlin
BuiltinFunction(
    "blitMono5x7Packed",
    listOf("Int", "Int", "Int", "Long", "Int", "Int"),
    "Unit",
    "Draws a fixed 5x7 monochrome bitmap from one packed 35-bit glyph value.",
),
```

- [ ] **Step 5: Decode packed glyph in runtime bridge**

In `RuntimeHostBridge.invokeDisplay(...)`, add this branch after `"blitMono5x7"`:

```kotlin
"blitMono5x7Packed" -> {
    val glyph = arguments[3].asLong()
    runtime.display.blitMono5x7(
        arguments[0].asInt(),
        arguments[1].asInt(),
        arguments[2].asInt(),
        ((glyph shr 30) and 0b11111).toInt(),
        ((glyph shr 25) and 0b11111).toInt(),
        ((glyph shr 20) and 0b11111).toInt(),
        ((glyph shr 15) and 0b11111).toInt(),
        ((glyph shr 10) and 0b11111).toInt(),
        ((glyph shr 5) and 0b11111).toInt(),
        (glyph and 0b11111).toInt(),
        arguments[4].asInt(),
        arguments[5].asInt(),
    )
    VmValue.UnitValue
}
```

- [ ] **Step 6: Run tests to verify Task 1 passes**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesPackedNumericGlyphDisplayBuiltin --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.packedGlyphDisplayBuiltinDecodesRowsThroughRuntimeBridge
```

Expected: both tests pass.

- [ ] **Step 7: Commit Task 1**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: add packed 5x7 display blit"
```

## Task 2: Migrate ROM Terminal Glyphs to Packed Longs

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`

- [ ] **Step 1: Write failing ROM source regression tests**

Update `bundledRomTerminalHasSymmetricAngleGlyphs` to check packed literals:

```kotlin
assertTrue(
    source.contains("if (ch == \">\") { return 0b10000010000010000010001000100010000L }"),
    "terminal.ck should use a balanced packed seven-row '>' glyph",
)
assertTrue(
    source.contains("if (ch == \"<\") { return 0b00001000100010001000001000001000001L }"),
    "terminal.ck should use a balanced packed seven-row '<' glyph",
)
```

Replace `bundledRomTerminalUsesNumericGlyphRows` with `bundledRomTerminalUsesPackedBitwiseGlyphs`:

```kotlin
@Test
fun bundledRomTerminalUsesPackedBitwiseGlyphs() {
    val source = resourceText("rom/terminal.ck")

    assertTrue(source.contains("fun glyphBits(ch: String): Long"), "terminal.ck should map characters to packed glyph bits")
    assertTrue(source.contains("display::blitMono5x7Packed"), "terminal.ck should render glyphs through the packed display API")
    assertFalse(source.contains("pub struct Glyph5x7"), "terminal.ck should not allocate glyph row structs")
    assertFalse(source.contains("fun glyphRows(ch: String): Glyph5x7"), "terminal.ck should not return glyph row structs")
    assertFalse(source.contains("Glyph5x7("), "terminal.ck should not construct glyph row structs")
    assertFalse(source.contains("fun glyphPattern(ch: String): String"), "terminal.ck should not keep string glyph masks")
    assertFalse(Regex("return \\\"[01]{35}\\\"").containsMatchIn(source), "terminal.ck should not return 35-character string glyph masks")
    assertTrue(
        source.contains("if (ch == \">\") { return 0b10000010000010000010001000100010000L }"),
        "terminal.ck should preserve the balanced '>' glyph as packed bits",
    )
    assertTrue(
        source.contains("if (ch == \"<\") { return 0b00001000100010001000001000001000001L }"),
        "terminal.ck should preserve the balanced '<' glyph as packed bits",
    )
}
```

- [ ] **Step 2: Run ROM test to verify it fails**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomTerminalUsesPackedBitwiseGlyphs
```

Expected: test fails because `terminal.ck` still contains `Glyph5x7` and `glyphRows(...)`.

- [ ] **Step 3: Replace terminal glyph model**

In `terminal.ck`:

1. Delete the `pub struct Glyph5x7` line.
2. Rename `fun glyphRows(ch: String): Glyph5x7` to `fun glyphBits(ch: String): Long`.
3. Convert every `return Glyph5x7(row0 = A, row1 = B, row2 = C, row3 = D, row4 = E, row5 = F, row6 = G)` into one packed literal:

```text
0b<row0 as 5 bits><row1 as 5 bits><row2 as 5 bits><row3 as 5 bits><row4 as 5 bits><row5 as 5 bits><row6 as 5 bits>L
```

Required known conversions:

```ck
A: 0b01110100011000111111100011000110001L
>: 0b10000010000010000010001000100010000L
<: 0b00001000100010001000001000001000001L
fallback: 0b11111100011000110001100011000111111L
```

4. In `drawGlyph(...)`, replace:

```ck
val glyph: Glyph5x7 = glyphRows(ch)
display::blitMono5x7(displayId, x, y, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, color, -1)
```

with:

```ck
val glyph: Long = glyphBits(ch)
display::blitMono5x7Packed(displayId, x, y, glyph, color, -1)
```

5. In `renderTextRow(...)`, replace the non-space branch with:

```ck
val glyph: Long = glyphBits(ch)
display::blitMono5x7Packed(displayId, col * 6, row * 9, glyph, 2016, -1)
```

Keep the existing space fast path and all terminal control-flow behavior unchanged.

- [ ] **Step 4: Run ROM tests to verify Task 2 passes**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest
```

Expected: all ROM script compile tests pass.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: pack rom terminal glyphs"
```

## Task 3: Documentation and Full Verification

**Files:**
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Document packed display builtin**

In the display API section of `docs/LANGUAGE.md`, add:

```markdown
- `blitMono5x7Packed(displayId: Int, x: Int, y: Int, glyph: Long, foreground: Int, background: Int): Unit`
+  draws a fixed 5x7 monochrome bitmap from one packed 35-bit glyph value. Rows are stored as seven 5-bit masks from top to bottom, with row 0 in bits 34..30 and row 6 in bits 4..0. `background < 0` keeps background pixels transparent.
```

- [ ] **Step 2: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run ROM compile regression**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Check whitespace and repository status**

Run:

```bash
git diff --check
git status --branch --short
```

Expected: `git diff --check` prints no output and status shows only intended docs change before commit.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
git add docs/LANGUAGE.md
git commit -m "docs: document packed glyph display api"
```

- [ ] **Step 7: Final status**

Run:

```bash
git status --branch --short
git log --oneline --decorate --max-count=8
```

Expected: working tree is clean on `feature/packed-bitwise-glyphs`.
