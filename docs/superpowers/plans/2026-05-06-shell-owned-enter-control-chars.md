# Shell-Owned Enter Control Characters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Enter echo shell-owned, fix empty Enter prompt scrolling behavior, and add minimal CKL/terminal control character support.

**Architecture:** `terminal.ck` keeps editing input as an overlay but stops committing `line + "\n"` locally on Enter. `shell.ck` echoes the submitted line with `write(ctx, line + "\n")` immediately after `readLine(ctx)`, then processes the command. `terminal.ck` renders `\n`, `\r`, and `\b`; CKL lexer/formatter support authoring and preserving `\r`/`\b` escapes, and Gradle copies `.ck` resources raw so those escapes are not corrupted before CKL compilation.

**Tech Stack:** CKL ROM scripts, Kotlin compiler/frontend tests, Kotlin ROM resource tests, Gradle.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: add `\r` string escape support in the lexer.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`: format `\r` and `\b` as escaped text.
- Modify `build-scripts/src/main/kotlin/metadata.gradle.kts`: copy `.ck` resources raw instead of applying `expand(...)` to them.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`: add lexer regression for `\r`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`: add formatter regression for `\r`/`\b`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`: add `\r`/`\b` handling and remove local Enter commit.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`: echo entered line after `readLine(ctx)`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`: add ROM source regressions.

### Task 1: CKL String Control Escapes

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`

- [ ] **Step 1: Write failing lexer test**

Add after `lexesBackspaceEscapeInStringLiteral()`:

```kotlin
    @Test
    fun lexesCarriageReturnEscapeInStringLiteral() {
        val tokens = Lexer("pub fun main() { system::log(\"\\r\"); }").lex()

        assertEquals("\r", tokens.single { it.kind == TokenKind.STRING }.text)
    }
```

- [ ] **Step 2: Write failing formatter test**

Add to `LanguageFormatterTest` after `formatReturnsNoEditsWhenSourceIsAlreadyCanonical()`:

```kotlin
    @Test
    fun formatEscapesTerminalControlCharactersInStrings() {
        val source = "pub fun main(){system::log(\"\r\b\");}"

        val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

        assertEquals(
            """
            pub fun main() {
                system::log("\r\b")
            }
            """.trimIndent() + "\n",
            formatted,
        )
    }
```

- [ ] **Step 3: Run tests to verify RED**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesCarriageReturnEscapeInStringLiteral --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatEscapesTerminalControlCharactersInStrings`

Expected: FAIL because `\r` currently lexes as `r`, and formatter does not escape raw `\r`/`\b`.

- [ ] **Step 4: Implement lexer and formatter support**

In `LanguageFrontend.kt`, update `lexString` escape mapping:

```kotlin
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
```

In `LanguageFormatter.kt`, update `escapeString()` mapping:

```kotlin
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '\b' -> "\\b"
```

- [ ] **Step 5: Run tests to verify GREEN**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.lexesCarriageReturnEscapeInStringLiteral --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatEscapesTerminalControlCharactersInStrings`

Expected: BUILD SUCCESSFUL.

### Task 2: Raw CKL Resource Copying

**Files:**
- Modify: `build-scripts/src/main/kotlin/metadata.gradle.kts`

- [ ] **Step 1: Copy CKL resources without template expansion**

Replace the single expanded copy spec:

```kotlin
        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
```

with separate specs:

```kotlin
        from(from) {
            exclude { element -> element.name.contains(".png") || element.name.endsWith(".ck") }
            expand(replaceProperties)
        }
        from(from) {
            include("**/*.ck")
        }

        into(intoDir)
```

- [ ] **Step 2: Verify processed CKL resources preserve escapes**

Run: `./gradlew :v1_21_1-neoforge:processResources && grep -n 'write(ctx, line + "\\n")' modules/v1_21_1/v1_21_1-neoforge/build/resources/main/rom/shell.ck`

Expected: grep prints the `write(ctx, line + "\\n")` line with a textual backslash-n escape.

### Task 3: ROM Enter Ownership and Terminal Control Rendering

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`

- [ ] **Step 1: Write failing ROM regression test**

Add after `bundledRomTerminalUsesSingleVisibleStreamForStdoutAndStderr()`:

```kotlin
    @Test
    fun bundledRomShellOwnsSubmittedLineEchoAndTerminalHandlesControlChars() {
        val terminal = resourceText("rom/terminal.ck")
        val shell = resourceText("rom/shell.ck")

        assertTrue(
            shell.contains("val line: String = readLine(ctx)\n        write(ctx, line + \"\\\\n\")"),
            "shell.ck should echo submitted lines so blank Enter is shell-owned visible output",
        )
        assertFalse(
            terminal.contains("buffer = appendText(displayId, buffer, line + \"\\\\n\")"),
            "terminal.ck must not locally commit submitted lines on Enter",
        )
        assertTrue(terminal.contains("ch == \"\\\\r\""), "terminal.ck should handle carriage return output")
        assertTrue(terminal.contains("ch == \"\\\\b\""), "terminal.ck should handle backspace output")
        assertTrue(terminal.contains("clearCell"), "terminal.ck should clear a cell for backspace output")
    }
```

- [ ] **Step 2: Run test to verify RED**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomShellOwnsSubmittedLineEchoAndTerminalHandlesControlChars`

Expected: FAIL because shell does not echo after `readLine`, terminal still locally commits Enter, and `\r`/`\b` rendering is absent.

- [ ] **Step 3: Add terminal cell clearing helper**

In `terminal.ck`, add after `drawGlyph(...)`:

```ck
fun clearCell(displayId: Int, column: Int, row: Int) {
    display::fillRect(displayId, column * 6, row * 9, 6, 9, 0)
}
```

- [ ] **Step 4: Add control char handling to `appendText()`**

Inside `appendText()`, after the `if (ch == "\n") { ... }` branch and before the printable-character `else`, add:

```ck
        } else if (ch == "\r") {
            if (dirtyRow >= 0) {
                cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                dirtyRow = 0 - 1
                dirtyText = ""
            }
            col = 0
        } else if (ch == "\b") {
            if (dirtyRow >= 0) {
                cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                dirtyRow = 0 - 1
                dirtyText = ""
            }
            if (col > 0) {
                col = col - 1
                cells = replaceRange(cells, row * cols + col, " ")
                clearCell(displayId, col, row)
            }
```

- [ ] **Step 5: Remove terminal local Enter commit**

Replace Enter handling in `terminal.ck`:

```ck
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        buffer = appendText(displayId, buffer, line + "\n")
                        line = ""
```

with:

```ck
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        line = ""
```

- [ ] **Step 6: Add shell-owned echo**

In `shell.ck`, after `val line: String = readLine(ctx)`, add:

```ck
        write(ctx, line + "\n")
```

- [ ] **Step 7: Run ROM test to verify GREEN**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledRomShellOwnsSubmittedLineEchoAndTerminalHandlesControlChars`

Expected: BUILD SUCCESSFUL.

### Task 4: Full Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run compiler tests**

Run: `./gradlew :compiler:test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run ROM tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full suite and diff hygiene**

Run: `./gradlew test && git diff --check`

Expected: BUILD SUCCESSFUL and no `git diff --check` output.
