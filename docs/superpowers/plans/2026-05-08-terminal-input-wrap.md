# Terminal Input Wrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render typed terminal input across terminal cell bounds instead of clipping it at the right edge.

**Architecture:** Keep the change in bundled CKL `rom/terminal.ck`. Treat typed input as an overlay separate from committed terminal output, track the previously rendered input text, and clear/redraw wrapped overlay rows on every input change. Do not modify Kotlin `ScreenBuffer` or renderer behavior.

**Tech Stack:** CKL ROM scripts, Kotlin source-level ROM tests, Gradle.

---

## File Structure

- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
  - Replace single-row `renderInputLine(displayId, buffer, line)` with wrapped overlay helpers and a previous/current render entrypoint.
  - Track `renderedLine` in `main`.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
  - Add a source-level regression test for wrapped input overlay structure.

---

### Task 1: RED source regression

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Add failing source-level test**

Add this test after `bundledRomTerminalHandlesControlChars`-adjacent terminal tests, before `bundledRomTerminalHasSymmetricAngleGlyphs`:

```kotlin
    @Test
    fun bundledRomTerminalWrapsRenderedInputOverlayByDisplayBounds() {
        val source = resourceText("rom/terminal.ck")

        assertTrue(
            source.contains("fun inputOverlayRows(displayId: Int, buffer: TerminalBuffer, line: String): Int"),
            "terminal.ck should calculate how many rows a typed input overlay occupies",
        )
        assertTrue(
            source.contains("fun clearRenderedInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String)"),
            "terminal.ck should clear every row previously occupied by typed input",
        )
        assertTrue(
            source.contains("fun renderInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String, line: String)"),
            "terminal.ck should render typed input with access to the previous overlay text",
        )
        assertTrue(
            source.contains("if (x >= cols)"),
            "terminal.ck should wrap typed input when it reaches the right display bound",
        )
        assertTrue(
            source.contains("x = 0\n            y = y + 1"),
            "terminal.ck should continue wrapped typed input on the next row",
        )
        assertTrue(
            source.contains("var renderedLine: String = \"\""),
            "terminal.ck should track the last rendered input overlay",
        )
        assertTrue(
            source.contains("renderInputLine(displayId, buffer, renderedLine, line)"),
            "terminal.ck should redraw input using previous and current overlay text",
        )
    }
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomTerminalWrapsRenderedInputOverlayByDisplayBounds'
```

Expected: FAIL because the wrapped overlay helpers and `renderedLine` state do not exist yet.

---

### Task 2: Implement CKL wrapped input overlay

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`

- [ ] **Step 1: Replace `renderInputLine` helper**

Replace the existing `renderInputLine(displayId: Int, buffer: TerminalBuffer, line: String)` function with:

```ck
fun inputOverlayRows(displayId: Int, buffer: TerminalBuffer, line: String): Int {
    val cols: Int = columns(displayId)
    if (cols <= 0) {
        return 0
    }
    if (strings::length(line) <= 0) {
        return 1
    }
    var rowsUsed: Int = 1
    var x: Int = buffer.cursorColumn
    var i: Int = 0
    while i < strings::length(line) {
        x = x + 1
        if (x >= cols) {
            x = 0
            if (i + 1 < strings::length(line)) {
                rowsUsed = rowsUsed + 1
            }
        }
        i = i + 1
    }
    return rowsUsed
}

fun clearRenderedInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String) {
    val rowsUsed: Int = inputOverlayRows(displayId, buffer, previousLine)
    var rowOffset: Int = 0
    while rowOffset < rowsUsed + 0 {
        val row: Int = buffer.cursorRow + rowOffset
        if (row >= 0 && row < rows(displayId)) {
            clearTextRow(displayId, row)
            renderTextRow(displayId, buffer.cellsText, row)
        }
        rowOffset = rowOffset + 1
    }
}

fun renderInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String, line: String) {
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    var x: Int = buffer.cursorColumn
    var y: Int = buffer.cursorRow
    if (rs <= 0 || cols <= 0) {
        return
    }
    if (y < 0 || y >= rs) {
        return
    }
    if (x < 0 || x >= cols) {
        return
    }
    clearRenderedInputLine(displayId, buffer, previousLine)
    var i: Int = 0
    while i < strings::length(line) {
        if (y >= rs) {
            display::present(displayId)
            return
        }
        if (x >= cols) {
            x = 0
            y = y + 1
            if (y >= rs) {
                display::present(displayId)
                return
            }
        }
        drawGlyph(displayId, x, y, strings::charAt(line, i), 2016)
        x = x + 1
        i = i + 1
    }
    display::present(displayId)
}
```

- [ ] **Step 2: Track `renderedLine` in `main`**

After `var line: String = ""`, add:

```ck
    var renderedLine: String = ""
```

Update each input redraw call:

```ck
renderInputLine(displayId, buffer, renderedLine, line)
renderedLine = line
```

When display geometry changes and `line = ""`, also add:

```ck
                        renderedLine = ""
```

When Enter submits the current line, after `line = ""`, add:

```ck
                        renderedLine = ""
```

- [ ] **Step 3: Run GREEN source test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomTerminalWrapsRenderedInputOverlayByDisplayBounds'
```

Expected: PASS.

---

### Task 3: Compile and image audit verification

**Files:**
- No production changes beyond Task 2.

- [ ] **Step 1: Run ROM compile tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.RomScriptCompileTest'
```

Expected: PASS.

- [ ] **Step 2: Run bundled image audit**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected: PASS.

- [ ] **Step 3: Check status and diff**

Run:

```bash
git status --short --untracked-files=all
git diff --stat
git diff --check
```

Expected: only the spec, plan, `terminal.ck`, and `RomScriptCompileTest.kt` changes are present before commit; no whitespace errors.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-08-terminal-input-wrap-design.md \
    docs/superpowers/plans/2026-05-08-terminal-input-wrap.md \
    modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck \
    modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: wrap terminal input overlay"
```
