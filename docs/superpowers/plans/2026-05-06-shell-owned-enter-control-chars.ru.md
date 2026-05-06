# План реализации Shell-Owned Enter Control Characters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Цель:** Сделать Enter echo ответственностью shell, исправить поведение пустого Enter у prompt и добавить минимальную поддержку CKL/terminal control characters.

**Архитектура:** `terminal.ck` продолжает редактировать input как overlay, но больше не делает local commit `line + "\n"` на Enter. `shell.ck` echo-ит submitted line через `write(ctx, line + "\n")` сразу после `readLine(ctx)`, затем обрабатывает command. `terminal.ck` рендерит `\n`, `\r` и `\b`; CKL lexer/formatter поддерживают authoring и preservation для `\r`/`\b` escapes, а Gradle копирует `.ck` resources raw, чтобы эти escapes не портились до CKL compilation.

**Tech Stack:** CKL ROM scripts, Kotlin compiler/frontend tests, Kotlin ROM resource tests, Gradle.

---

## Структура файлов

- Изменить `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: добавить поддержку `\r` string escape в lexer.
- Изменить `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`: форматировать `\r` и `\b` как escaped text.
- Изменить `build-scripts/src/main/kotlin/metadata.gradle.kts`: копировать `.ck` resources raw вместо применения `expand(...)` к ним.
- Изменить `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`: добавить lexer regression для `\r`.
- Изменить `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`: добавить formatter regression для `\r`/`\b`.
- Изменить `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`: добавить `\r`/`\b` handling и убрать local Enter commit.
- Изменить `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`: echo entered line после `readLine(ctx)`.
- Изменить `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`: добавить ROM source regressions.

### Task 1: CKL String Control Escapes

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`

- [ ] **Step 1: Write failing lexer test**

Добавить после `lexesBackspaceEscapeInStringLiteral()`:

```kotlin
    @Test
    fun lexesCarriageReturnEscapeInStringLiteral() {
        val tokens = Lexer("pub fun main() { system::log(\"\\r\"); }").lex()

        assertEquals("\r", tokens.single { it.kind == TokenKind.STRING }.text)
    }
```

- [ ] **Step 2: Write failing formatter test**

Добавить в `LanguageFormatterTest` после `formatReturnsNoEditsWhenSourceIsAlreadyCanonical()`:

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

Expected: FAIL, потому что `\r` сейчас lexes как `r`, а formatter не escape-ит raw `\r`/`\b`.

- [ ] **Step 4: Implement lexer and formatter support**

В `LanguageFrontend.kt` обновить escape mapping в `lexString`:

```kotlin
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
```

В `LanguageFormatter.kt` обновить mapping в `escapeString()`:

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

Заменить single expanded copy spec:

```kotlin
        from(from) { exclude { it.name.contains(".png") } }

        into(intoDir)

        expand(replaceProperties)
```

на separate specs:

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

Добавить после `bundledRomTerminalUsesSingleVisibleStreamForStdoutAndStderr()`:

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

Expected: FAIL, потому что shell ещё не echo-ит после `readLine`, terminal всё ещё делает local Enter commit, а `\r`/`\b` rendering отсутствует.

- [ ] **Step 3: Add terminal cell clearing helper**

В `terminal.ck` добавить после `drawGlyph(...)`:

```ck
fun clearCell(displayId: Int, column: Int, row: Int) {
    display::fillRect(displayId, column * 6, row * 9, 6, 9, 0)
}
```

- [ ] **Step 4: Add control char handling to `appendText()`**

Внутри `appendText()`, после branch `if (ch == "\n") { ... }` и перед printable-character `else`, добавить:

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

Заменить Enter handling в `terminal.ck`:

```ck
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        buffer = appendText(displayId, buffer, line + "\n")
                        line = ""
```

на:

```ck
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        line = ""
```

- [ ] **Step 6: Add shell-owned echo**

В `shell.ck`, после `val line: String = readLine(ctx)`, добавить:

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
