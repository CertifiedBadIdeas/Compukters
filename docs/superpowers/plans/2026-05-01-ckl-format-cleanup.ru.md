# План реализации CKL Format Cleanup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить CKL Format Document и Cleanup Document с сохранением комментариев и автосортировкой imports.

**Architecture:** Сначала расширить lexer/parser pipeline comment trivia, затем добавить отдельный `LanguageFormatter`, который печатает CKL из AST в canonical text и возвращает `TextEdit`. Потом прокинуть formatter через `LanguageIde`, `IdeFacade`, `DeviceIdeHost` и workbench gateway; cleanup переиспользует formatter и консервативно удаляет unused imports через semantic metadata.

**Tech Stack:** Kotlin 2.3, Gradle, compiler/frontend module, runtime `TextEdit`, kotlin.test/JUnit.

---

## Структура файлов

- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
  - `FormatOptions`, `FormatResult`, `LanguageFormatter`, `CklWriter`, AST render helpers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
  - `CommentKind`, `CommentTrivia`, `ParsedSource.comments`, formatter methods в `IdeFacade`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
  - `Lexer` собирает comments; analyzer добавляет import metadata.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
  - Делегирует format/cleanup в `LanguageFormatter`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`
  - Request/response и host methods для format/cleanup.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
  - Pass-through в `LanguageIde`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
  - Workbench facade methods, если нужны существующему gateway pattern.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
  - Expose format/cleanup через common gateway.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
  - Tests для formatter, cleanup, comments, idempotence.
- Modify `docs/LANGUAGE.md`
  - Документация Format Document/Cleanup.

---

## Task 1: Comment trivia в lexer/parser pipeline

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Написать failing test для comment trivia**

Создать `LanguageFormatterTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageFormatterTest {
    private val parser = DefaultParserFacade()

    @Test
    fun parserPreservesLineAndBlockCommentsAsTrivia() {
        val source =
            """
            // leading file comment
            import terminal { println }; /* import note */

            /* before main */
            fun main() { // inline body
                println("hi");
            }
            """.trimIndent()

        val parsed = parser.parse("main.ck", source)

        assertEquals(emptyList(), parsed.syntaxDiagnostics.map { it.message })
        assertEquals(
            listOf(CommentKind.LINE, CommentKind.BLOCK, CommentKind.BLOCK, CommentKind.LINE),
            parsed.comments.map { it.kind },
        )
        assertTrue(parsed.comments[0].text.contains("leading file comment"))
        assertTrue(parsed.comments[1].text.contains("import note"))
        assertTrue(parsed.comments[2].text.contains("before main"))
        assertTrue(parsed.comments[3].text.contains("inline body"))
    }
}
```

- [ ] **Step 2: Запустить RED test**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*parserPreservesLineAndBlockCommentsAsTrivia'
```

Expected: compilation fails, потому что нет `CommentKind` и `ParsedSource.comments`.

- [ ] **Step 3: Добавить comment trivia models**

В `FrontendPipelines.kt` добавить `SourceRange` import, `CommentKind`, `CommentTrivia`, и поле `comments` в `ParsedSource`. В `DefaultParserFacade.parse()` передать `comments = lexer.comments`.

Код моделей:

```kotlin
enum class CommentKind { LINE, BLOCK }

data class CommentTrivia(
    val kind: CommentKind,
    val text: String,
    val range: SourceRange,
)
```

- [ ] **Step 4: Собирать comments в Lexer**

В `Lexer` добавить `mutableComments`, public getter `comments`, заменить skip line comment на `lexLineComment(start)`, а `lexBlockComment(start)` сделать записывающим `CommentTrivia` при успешном закрытии.

Ключевой код:

```kotlin
private fun lexLineComment(start: SourceLocation) {
    val textStart = index
    while (!isAtEnd() && peek() != '\n') advance()
    mutableComments += CommentTrivia(CommentKind.LINE, source.substring(textStart, index), SourceRange(start, location()))
}
```

Block comment должен записывать `CommentKind.BLOCK` с text между `/*` и `*/`, а при EOF оставить diagnostic `Unterminated block comment.`.

- [ ] **Step 5: Проверить GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*parserPreservesLineAndBlockCommentsAsTrivia'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): preserve ckl comment trivia"
```

---

## Task 2: Formatter API и no-op для invalid source

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Добавить failing tests**

Добавить tests `formatReturnsNoEditsForSyntaxErrors()` и `formatReturnsNoEditsWhenSourceIsAlreadyCanonical()` с `LanguageFormatter`: invalid source `fun main() { val x = ;` должен вернуть empty edits и diagnostic `Cannot format source with syntax errors`; canonical source `fun main() {\n    terminal::println(\"hi\");\n}\n` должен вернуть empty edits и `changed == false`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatReturnsNoEditsForSyntaxErrors' --tests '*LanguageFormatterTest*formatReturnsNoEditsWhenSourceIsAlreadyCanonical'
```

Expected: compilation fails, `LanguageFormatter` отсутствует.

- [ ] **Step 3: Создать `LanguageFormatter.kt`**

Добавить `FormatOptions`, `FormatResult`, `LanguageFormatter.formatDocument()`, `cleanupDocument()`, `renderCanonical()`, `ensureTrailingNewline()`.

Минимальная реализация: parse source, если есть `FrontendSeverity.ERROR` — вернуть `FormatResult(emptyList(), listOf(Diagnostic(...)))`; иначе вернуть full-document edit только если `renderCanonical(parsed) != source`.

- [ ] **Step 4: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatReturnsNoEditsForSyntaxErrors' --tests '*LanguageFormatterTest*formatReturnsNoEditsWhenSourceIsAlreadyCanonical'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): add ckl formatter API"
```

---

## Task 3: Canonical AST rendering для CKL syntax

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Добавить failing tests**

Добавить `formatsFunctionsStructsClassesAndControlFlow()` и `formatIsIdempotent()`. Ожидаемый output: sorted imports, blank lines, `struct Vec2 { x: Int, y: Int }`, class blocks, `init`, methods, `if/else`, `while`, named constructor calls with spaces.

Также добавить helpers:

```kotlin
private fun applySingleEdit(source: String, result: FormatResult): String
private fun applyEdits(source: String, edits: List<TextEdit>): String
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatsFunctionsStructsClassesAndControlFlow' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: first test fails because renderer ещё не canonical.

- [ ] **Step 3: Implement `CklWriter`**

Добавить writer с `write`, `line`, `blankLine`, `indented`, `result`. Indent — 4 spaces.

- [ ] **Step 4: Implement render functions**

Заменить `renderCanonical()` на AST renderer. Добавить render helpers для imports, top-level declarations, structs, classes, functions, blocks, statements, expressions, type syntax.

Expression rendering должен учитывать precedence: `||`, `&&`, equality, comparisons, `+/-`, `*/`, unary, calls/member/scope access.

Statements с semicolon: `val/var`, assignment, member assignment, expression statement, `return`.

- [ ] **Step 5: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatsFunctionsStructsClassesAndControlFlow' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): render canonical ckl syntax"
```

---

## Task 4: Сохранять comments в formatted output

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Failing test**

Добавить `formatPreservesLeadingInlineAndBlockComments()` с source, где есть file comment, import inline comment, block comment перед `fun main`, body comment и block comment после call. Проверить `formatted.contains(...)` для всех comments.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatPreservesLeadingInlineAndBlockComments'
```

Expected: fails, comments ещё не печатаются.

- [ ] **Step 3: Implement comment planner**

Добавить `CommentPlanner`, который хранит comments sorted by range и отдаёт `takeBefore(offset)`. Thread planner through import/declaration/statement render. Перед каждым construct печатать leading comments через `renderLeadingComments`. В конце файла вывести remaining comments.

- [ ] **Step 4: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatPreservesLeadingInlineAndBlockComments' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): preserve comments while formatting"
```

---

## Task 5: Import sorting/merging во время Format Document

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Failing tests**

Добавить:

- `formatSortsAndMergesSelectiveImports()` — input с `"z.ck"`, `terminal`, двумя `"a.ck"`; expected: `"a.ck" { Alpha, Beta }`, `"z.ck" { Zebra }`, `terminal { println, write }`.
- `formatDoesNotRemoveUnusedImports()` — `clear` остаётся при обычном format.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatSortsAndMergesSelectiveImports' --tests '*LanguageFormatterTest*formatDoesNotRemoveUnusedImports'
```

Expected: sorting/merging test fails.

- [ ] **Step 3: Implement import normalization**

В `LanguageFormatter.kt` добавить normalized import model. For `ImportMode.Selective`: group by `source.displayText()`, merge items, sort item names. Namespace aliases keep as separate imports, sorted by source+alias. Format Document never removes unused imports.

- [ ] **Step 4: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatSortsAndMergesSelectiveImports' --tests '*LanguageFormatterTest*formatDoesNotRemoveUnusedImports'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): organize imports during format"
```

---

## Task 6: Cleanup консервативно удаляет unused imports

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Failing cleanup tests**

Добавить tests:

- `cleanupRemovesUnusedSelectiveImportItems()` — `terminal { clear, println, write }` becomes `terminal { println }`.
- `cleanupPreservesUsedFunctionStructAndClassImports()` — imports `Counter`, `Vec2`, `make` from `model.ck`, all remain.
- `cleanupReturnsNoEditsWhenAnalysisHasErrors()` — если `missing()` unresolved, cleanup returns empty edits.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*cleanupRemovesUnusedSelectiveImportItems' --tests '*LanguageFormatterTest*cleanupPreservesUsedFunctionStructAndClassImports' --tests '*LanguageFormatterTest*cleanupReturnsNoEditsWhenAnalysisHasErrors'
```

Expected: cleanup removal test fails.

- [ ] **Step 3: Add semantic import metadata**

Добавить `ImportItemBindingInfo(sourceText, itemName, itemRange, symbol)` и `SemanticResult.importItemBindings`. В `SemanticAnalyzer` заполнять list при selected builtin/file function, record, class imports.

- [ ] **Step 4: Implement cleanup filtering**

В `cleanupDocument`: parse; если syntax errors — cannot format. Analyze через `LanguageFrontend().analyze(name, source, loader)`; если ERROR diagnostics — `FormatResult(emptyList())`. Собрать `referencedSymbols = analysis.references.map { it.target }.toSet()`. Удалить selective items, чьи symbols не referenced. Empty groups удалить. Namespace aliases оставить, если нет надёжного alias-reference check.

- [ ] **Step 5: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*cleanupRemovesUnusedSelectiveImportItems' --tests '*LanguageFormatterTest*cleanupPreservesUsedFunctionStructAndClassImports' --tests '*LanguageFormatterTest*cleanupReturnsNoEditsWhenAnalysisHasErrors'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compiler tests**

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(formatter): cleanup unused ckl imports"
```

---

## Task 7: IDE facade, device host и workbench wiring

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Failing IDE tests**

Добавить `languageIdeFormatsDocument()` и `languageIdeCleansDocument()`: `LanguageIde().formatDocument(...)` возвращает full-document edit с canonical function; `cleanupDocument(...)` удаляет unused `clear`.

- [ ] **Step 2: Run RED**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*languageIdeFormatsDocument' --tests '*LanguageFormatterTest*languageIdeCleansDocument'
```

Expected: compilation fails, methods отсутствуют.

- [ ] **Step 3: Extend `IdeFacade` and `LanguageIde`**

Добавить methods `formatDocument(name, source): FormatResult` и `cleanupDocument(name, source, loader): FormatResult`. В `LanguageIde` создать/delegate `LanguageFormatter(parser)`.

- [ ] **Step 4: Runtime request/response models**

В `DeviceIdeHost.kt` добавить `DeviceFormatRequest`, `DeviceFormatResponse`, `DeviceCleanupRequest`, `DeviceCleanupResponse`; расширить `DeviceIdeHost` methods `format(...)` и `cleanup(...)`.

- [ ] **Step 5: Wire `WorkspaceDeviceIdeHost`**

Реализовать methods по pattern `complete/hover/definition`: читать workspace document, вызвать `ide(deviceId).formatDocument` или `.cleanupDocument`, вернуть edits/diagnostics.

- [ ] **Step 6: Wire workbench gateway/contracts**

По существующему `complete` pattern добавить format/cleanup methods в contracts/gateway с минимальным surface.

- [ ] **Step 7: Targeted tests**

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*languageIdeFormatsDocument' --tests '*LanguageFormatterTest*languageIdeCleansDocument'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Full tests**

```bash
./gradlew :compiler:test
./gradlew test
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt
git commit -m "feat(ide): expose ckl format and cleanup"
```

---

## Task 8: Documentation, final verification и tag

**Files:**
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Update language docs**

Добавить раздел `Formatting and Cleanup`: Format Document canonicalizes valid CKL source, сортирует/объединяет imports, сохраняет comments; Cleanup делает то же и удаляет unused selective import items при reliable semantic proof; invalid source/errors возвращают no edits.

- [ ] **Step 2: Search contradictions**

```bash
grep -rnE 'Format Document|Cleanup Document|formatter|cleanup' docs modules/compiler/src/test --include='*.md' --include='*.kt' || true
```

Expected: нет docs, которые противоречат новой функциональности.

- [ ] **Step 3: Final verification**

```bash
./gradlew :compiler:test
./gradlew test
```

Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 4: Status**

```bash
git status --short
```

Expected: only intended doc changes before commit.

- [ ] **Step 5: Commit docs**

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document ckl format cleanup"
```

- [ ] **Step 6: Final clean verification and tag**

```bash
./gradlew :compiler:test
./gradlew test
git status --short
git tag -f ckl-format-cleanup-complete
```

Expected: tests pass, status clean, tag points at current `HEAD`.
