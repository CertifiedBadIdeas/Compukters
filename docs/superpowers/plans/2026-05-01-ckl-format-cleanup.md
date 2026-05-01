# CKL Format Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add CKL Format Document and Cleanup Document support with comment preservation and automatic import sorting.

**Architecture:** Add comment trivia to the existing lexer/parser pipeline, then add a focused `LanguageFormatter` service that renders CKL from AST into canonical text and returns `TextEdit`s. Wire the formatter through `LanguageIde`, `IdeFacade`, and device/workbench IDE hosts; cleanup reuses the formatter and adds conservative import removal using semantic metadata.

**Tech Stack:** Kotlin 2.3, Gradle, existing compiler/frontend module, existing runtime `TextEdit` model, kotlin.test/JUnit.

---

## File structure

- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
  - Owns `FormatOptions`, `FormatResult`, `LanguageFormatter`, `CklWriter`, and AST render helpers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
  - Add `CommentKind`, `CommentTrivia`, `ParsedSource.comments`, and formatter methods on `IdeFacade`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
  - Make `Lexer` collect comments instead of discarding them.
  - Add import metadata for cleanup.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
  - Delegate format and cleanup requests to `LanguageFormatter`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`
  - Add request/response models and host methods for format/cleanup.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
  - Pass device IDE format/cleanup requests to `LanguageIde`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
  - Add workbench IDE format/cleanup facade methods if needed by existing gateway pattern.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
  - Expose format/cleanup through the common workbench gateway.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
  - Formatter, cleanup, comments, and idempotence tests.
- Add or extend core/common tests only where host/gateway API wiring requires it.
- Modify `docs/LANGUAGE.md`
  - Document Format Document/Cleanup behavior.

---

## Task 1: Comment trivia in lexer/parser pipeline

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Write the failing comment trivia test**

Create `LanguageFormatterTest.kt` with this initial content:

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
            listOf(
                CommentKind.LINE,
                CommentKind.BLOCK,
                CommentKind.BLOCK,
                CommentKind.LINE,
            ),
            parsed.comments.map { it.kind },
        )
        assertTrue(parsed.comments[0].text.contains("leading file comment"))
        assertTrue(parsed.comments[1].text.contains("import note"))
        assertTrue(parsed.comments[2].text.contains("before main"))
        assertTrue(parsed.comments[3].text.contains("inline body"))
    }
}
```

- [ ] **Step 2: Run the RED test**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*parserPreservesLineAndBlockCommentsAsTrivia'
```

Expected: compilation fails because `CommentKind` and `ParsedSource.comments` do not exist.

- [ ] **Step 3: Add comment trivia models**

In `FrontendPipelines.kt`, add imports and models near `ParsedSource`:

```kotlin
import ru.lazyhat.compukterkraft.lang.api.SourceRange
```

```kotlin
enum class CommentKind {
    LINE,
    BLOCK,
}

data class CommentTrivia(
    val kind: CommentKind,
    val text: String,
    val range: SourceRange,
)
```

Change `ParsedSource` to include comments:

```kotlin
data class ParsedSource(
    val name: String,
    val source: String,
    val tokens: List<Token>,
    val comments: List<CommentTrivia>,
    val syntaxDiagnostics: List<FrontendDiagnostic>,
    val program: Program,
)
```

Update `DefaultParserFacade.parse()` to pass lexer comments:

```kotlin
return ParsedSource(
    name = name,
    source = source,
    tokens = tokens,
    comments = lexer.comments,
    syntaxDiagnostics = lexer.diagnostics + parser.diagnostics,
    program = program,
)
```

- [ ] **Step 4: Collect comments in Lexer**

In `LanguageFrontend.kt`, inside `Lexer`, add:

```kotlin
private val mutableComments = mutableListOf<CommentTrivia>()
val comments: List<CommentTrivia>
    get() = mutableComments.toList()
```

Replace line-comment skipping in the `/` branch with:

```kotlin
if (match('/')) {
    lexLineComment(start)
} else if (match('*')) {
    lexBlockComment(start)
} else if (match('=')) {
    addToken(TokenKind.SLASH_EQUAL, "/=", start)
} else {
    addToken(TokenKind.SLASH, "/", start)
}
```

Add `lexLineComment`:

```kotlin
private fun lexLineComment(start: SourceLocation) {
    val textStart = index
    while (!isAtEnd() && peek() != '\n') advance()
    mutableComments += CommentTrivia(
        kind = CommentKind.LINE,
        text = source.substring(textStart, index),
        range = SourceRange(start, location()),
    )
}
```

Update `lexBlockComment` so it records text on success:

```kotlin
private fun lexBlockComment(start: SourceLocation) {
    val textStart = index
    while (!isAtEnd()) {
        if (peek() == '*' && index + 1 < source.length && source[index + 1] == '/') {
            val text = source.substring(textStart, index)
            advance()
            advance()
            mutableComments += CommentTrivia(
                kind = CommentKind.BLOCK,
                text = text,
                range = SourceRange(start, location()),
            )
            return
        }
        advance()
    }
    diagnostics += FrontendDiagnostic("Unterminated block comment.", range(start))
}
```

- [ ] **Step 5: Run the test to verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*parserPreservesLineAndBlockCommentsAsTrivia'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run compiler tests**

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

## Task 2: Formatter API and invalid-source no-op behavior

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing tests for invalid source and no-change edits**

Append to `LanguageFormatterTest`:

```kotlin
private val formatter = LanguageFormatter()

@Test
fun formatReturnsNoEditsForSyntaxErrors() {
    val result = formatter.formatDocument("broken.ck", "fun main() { val x = ;")

    assertEquals(emptyList(), result.edits)
    assertTrue(result.diagnostics.any { it.message.contains("Cannot format source with syntax errors") })
}

@Test
fun formatReturnsNoEditsWhenSourceIsAlreadyCanonical() {
    val source = "fun main() {\n    terminal::println(\"hi\");\n}\n"

    val result = formatter.formatDocument("main.ck", source)

    assertEquals(emptyList(), result.edits)
    assertEquals(false, result.changed)
}
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatReturnsNoEditsForSyntaxErrors' --tests '*LanguageFormatterTest*formatReturnsNoEditsWhenSourceIsAlreadyCanonical'
```

Expected: compilation fails because `LanguageFormatter` does not exist.

- [ ] **Step 3: Implement formatter result API and minimal formatter skeleton**

Create `LanguageFormatter.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit

internal data class FormatOptions(
    val cleanup: Boolean = false,
)

internal data class FormatResult(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val changed: Boolean
        get() = edits.isNotEmpty()
}

internal class LanguageFormatter(
    private val parser: ParserFacade = DefaultParserFacade(),
) {
    fun formatDocument(
        name: String,
        source: String,
    ): FormatResult {
        val parsed = parser.parse(name, source)
        if (parsed.syntaxDiagnostics.any { it.severity == FrontendSeverity.ERROR }) {
            return FormatResult(
                edits = emptyList(),
                diagnostics = listOf(
                    Diagnostic(
                        message = "Cannot format source with syntax errors.",
                        severity = IdeDiagnosticSeverity.ERROR,
                    ),
                ),
            )
        }
        val formatted = renderCanonical(parsed)
        return if (formatted == source) {
            FormatResult(emptyList())
        } else {
            FormatResult(listOf(TextEdit(0, source.length, formatted)))
        }
    }

    fun cleanupDocument(
        name: String,
        source: String,
        loader: SourceLoader = NoOpSourceLoader,
    ): FormatResult = formatDocument(name, source)

    private fun renderCanonical(parsed: ParsedSource): String = parsed.source.ensureTrailingNewline()
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"
```

- [ ] **Step 4: Run targeted tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatReturnsNoEditsForSyntaxErrors' --tests '*LanguageFormatterTest*formatReturnsNoEditsWhenSourceIsAlreadyCanonical'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run compiler tests**

Run:

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

## Task 3: Canonical AST rendering for CKL syntax

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing formatting and idempotence tests**

Append to `LanguageFormatterTest`:

```kotlin
@Test
fun formatsFunctionsStructsClassesAndControlFlow() {
    val source = """
        import terminal { println };
        struct Vec2{x:Int,y:Int}
        class Counter(var value:Int){init{this.value=this.value+1;}fun current():Int{return this.value;}static fun zero():Counter{return Counter(value=0);}}
        fun main(){val v:Vec2=Vec2(x=1,y=2);if(v.x>0){println("x="+v.x);}else{println("none");}while v.y>0 { return; }}
    """.trimIndent()

    val expected = """
        import terminal { println };

        struct Vec2 { x: Int, y: Int }

        class Counter(var value: Int) {
            init {
                this.value = this.value + 1;
            }

            fun current(): Int {
                return this.value;
            }

            static fun zero(): Counter {
                return Counter(value = 0);
            }
        }

        fun main() {
            val v: Vec2 = Vec2(x = 1, y = 2);
            if (v.x > 0) {
                println("x=" + v.x);
            } else {
                println("none");
            }
            while v.y > 0 {
                return;
            }
        }
    """.trimIndent() + "\n"

    val result = formatter.formatDocument("main.ck", source)

    assertEquals(expected, applySingleEdit(source, result))
}

@Test
fun formatIsIdempotent() {
    val source = """
        import terminal { println };

        fun main() {
            println("hi");
        }
    """.trimIndent() + "\n"

    val first = formatter.formatDocument("main.ck", source)
    val once = applyEdits(source, first.edits)
    val second = formatter.formatDocument("main.ck", once)

    assertEquals(source, once)
    assertEquals(emptyList(), second.edits)
}

private fun applySingleEdit(
    source: String,
    result: FormatResult,
): String {
    assertEquals(1, result.edits.size)
    return applyEdits(source, result.edits)
}

private fun applyEdits(
    source: String,
    edits: List<TextEdit>,
): String {
    var current = source
    edits.sortedByDescending { it.startOffset }.forEach { edit ->
        current = current.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
    }
    return current
}
```

Add imports:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatsFunctionsStructsClassesAndControlFlow' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: the first test fails because the formatter currently returns normalized original source, not canonical AST output.

- [ ] **Step 3: Implement `CklWriter`**

In `LanguageFormatter.kt`, add a private writer:

```kotlin
private class CklWriter {
    private val builder = StringBuilder()
    private var indentLevel = 0
    private var lineStart = true

    fun write(text: String) {
        if (lineStart && text.isNotEmpty()) {
            repeat(indentLevel) { builder.append("    ") }
            lineStart = false
        }
        builder.append(text)
    }

    fun line() {
        builder.append('\n')
        lineStart = true
    }

    fun blankLine() {
        if (!builder.endsWith("\n")) line()
        if (!builder.endsWith("\n\n")) builder.append('\n')
        lineStart = true
    }

    fun indented(block: () -> Unit) {
        indentLevel += 1
        try {
            block()
        } finally {
            indentLevel -= 1
        }
    }

    fun result(): String = builder.toString().trimEnd() + "\n"
}
```

- [ ] **Step 4: Implement render entry points**

Replace `renderCanonical()` with AST rendering:

```kotlin
private fun renderCanonical(parsed: ParsedSource): String {
    val writer = CklWriter()
    val imports = parsed.program.imports.sortedWith(compareBy({ it.source.displayText() }, { it.range.start.offset }))
    imports.forEachIndexed { index, declaration ->
        if (index > 0) writer.line()
        writer.write(renderImport(declaration))
    }
    if (imports.isNotEmpty() && parsed.program.declarations.isNotEmpty()) writer.blankLine()
    parsed.program.declarations.forEachIndexed { index, declaration ->
        if (index > 0) writer.blankLine()
        renderTopLevel(writer, declaration)
    }
    return writer.result()
}
```

Add helpers:

```kotlin
private fun ImportSource.displayText(): String =
    when (this) {
        is ImportSource.BuiltinNamespace -> name
        is ImportSource.FilePath -> "\"$path\""
    }
```

Implement `renderImport()`, `renderTopLevel()`, `renderBlock()`, `renderStatement()`, `renderExpression()`, and `renderType()` in the same file. Use the current AST sealed types in `LanguageModel.kt`. For expressions, use precedence-aware printing so binary expressions keep needed parentheses.

Minimum expression precedence table:

```kotlin
private fun BinaryOperator.precedence(): Int =
    when (this) {
        BinaryOperator.OR -> 1
        BinaryOperator.AND -> 2
        BinaryOperator.EQUALS, BinaryOperator.NOT_EQUALS -> 3
        BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE -> 4
        BinaryOperator.PLUS, BinaryOperator.MINUS -> 5
        BinaryOperator.STAR, BinaryOperator.SLASH -> 6
    }
```

Render statements with semicolons where CKL requires them: variable declarations, assignments, member assignments, expression statements, and returns.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatsFunctionsStructsClassesAndControlFlow' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run compiler tests**

Run:

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

## Task 4: Preserve comments in formatted output

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing comment preservation tests**

Append:

```kotlin
@Test
fun formatPreservesLeadingInlineAndBlockComments() {
    val source = """
        // file comment
        import terminal { println }; // import comment

        /* main comment */
        fun main(){
        // body comment
        println("hi"); /* call comment */
        }
    """.trimIndent()

    val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

    assertTrue(formatted.contains("// file comment"), formatted)
    assertTrue(formatted.contains("// import comment"), formatted)
    assertTrue(formatted.contains("/* main comment */"), formatted)
    assertTrue(formatted.contains("// body comment"), formatted)
    assertTrue(formatted.contains("/* call comment */"), formatted)
}
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatPreservesLeadingInlineAndBlockComments'
```

Expected: fails because comments are not printed by the renderer yet.

- [ ] **Step 3: Add comment rendering support**

In `LanguageFormatter`, build a helper before rendering:

```kotlin
private class CommentPlanner(comments: List<CommentTrivia>) {
    private val pending = comments.sortedBy { it.range.start.offset }.toMutableList()

    fun takeBefore(offset: Int): List<CommentTrivia> {
        val result = pending.takeWhile { it.range.start.offset < offset }
        repeat(result.size) { pending.removeAt(0) }
        return result
    }

    fun takeRemaining(): List<CommentTrivia> = pending.toList().also { pending.clear() }
}
```

Thread `CommentPlanner` through render functions. Before each import, declaration, and statement, call:

```kotlin
private fun renderLeadingComments(
    writer: CklWriter,
    comments: List<CommentTrivia>,
) {
    comments.forEach { comment ->
        when (comment.kind) {
            CommentKind.LINE -> writer.write("//${comment.text}")
            CommentKind.BLOCK -> writer.write("/*${comment.text}*/")
        }
        writer.line()
    }
}
```

At the end of the file, render remaining comments so none are lost.

- [ ] **Step 4: Run targeted tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatPreservesLeadingInlineAndBlockComments' --tests '*LanguageFormatterTest*formatIsIdempotent'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run compiler tests**

Run:

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

## Task 5: Import sorting and merging during Format Document

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing import sorting tests**

Append:

```kotlin
@Test
fun formatSortsAndMergesSelectiveImports() {
    val source = """
        import "z.ck" { Zebra };
        import terminal { write, println };
        import "a.ck" { Beta };
        import "a.ck" { Alpha };
        fun main() { println("hi"); }
    """.trimIndent()

    val expected = """
        import "a.ck" { Alpha, Beta };
        import "z.ck" { Zebra };
        import terminal { println, write };

        fun main() {
            println("hi");
        }
    """.trimIndent() + "\n"

    val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

    assertEquals(expected, formatted)
}

@Test
fun formatDoesNotRemoveUnusedImports() {
    val source = """
        import terminal { clear, println };
        fun main() { println("hi"); }
    """.trimIndent()

    val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

    assertTrue(formatted.contains("clear"), formatted)
}
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatSortsAndMergesSelectiveImports' --tests '*LanguageFormatterTest*formatDoesNotRemoveUnusedImports'
```

Expected: sorting/merging test fails until import normalization is implemented.

- [ ] **Step 3: Implement import normalization**

Add a normalized import model inside `LanguageFormatter.kt`:

```kotlin
private data class NormalizedImport(
    val source: ImportSource,
    val mode: ImportMode,
    val firstOffset: Int,
)
```

In `renderCanonical`, replace raw import sorting with `normalizeImports(parsed.program.imports)`.

Rules:

- For `ImportMode.Selective`, group by `source.displayText()` and merge items by name.
- Sort merged item names alphabetically.
- For `ImportMode.Namespace`, keep each alias import as its own import and sort by source then alias.
- For `ImportMode.Invalid`, print the original mode conservatively if reachable; valid parsed source should not normally contain invalid imports.
- Sort built-in and file sources lexicographically by `source.displayText()`.

- [ ] **Step 4: Run targeted tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*formatSortsAndMergesSelectiveImports' --tests '*LanguageFormatterTest*formatDoesNotRemoveUnusedImports'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run compiler tests**

Run:

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

## Task 6: Cleanup removes unused imports conservatively

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing cleanup tests**

Append:

```kotlin
@Test
fun cleanupRemovesUnusedSelectiveImportItems() {
    val source = """
        import terminal { clear, println, write };
        fun main() { println("hi"); }
    """.trimIndent()

    val expected = """
        import terminal { println };

        fun main() {
            println("hi");
        }
    """.trimIndent() + "\n"

    val cleaned = applySingleEdit(source, formatter.cleanupDocument("main.ck", source))

    assertEquals(expected, cleaned)
}

@Test
fun cleanupPreservesUsedFunctionStructAndClassImports() {
    val loader =
        MapSourceLoader(
            mapOf(
                "main.ck" to """
                    import "model.ck" { Counter, Vec2, make };
                    fun main() {
                        val v: Vec2 = make();
                        val c: Counter = Counter(value = v.x);
                        terminal::println("v=" + c.value);
                    }
                """.trimIndent(),
                "model.ck" to """
                    struct Vec2 { x: Int, y: Int }
                    class Counter(var value: Int) {}
                    fun make(): Vec2 { return Vec2(x = 1, y = 2); }
                """.trimIndent(),
            ),
        )
    val source = loader.read("main.ck")!!

    val cleaned = applySingleEdit(source, formatter.cleanupDocument("main.ck", source, loader))

    assertTrue(cleaned.contains("Counter"), cleaned)
    assertTrue(cleaned.contains("Vec2"), cleaned)
    assertTrue(cleaned.contains("make"), cleaned)
}

@Test
fun cleanupReturnsNoEditsWhenAnalysisHasErrors() {
    val source = """
        import terminal { clear, println };
        fun main() { missing(); }
    """.trimIndent()

    val result = formatter.cleanupDocument("main.ck", source)

    assertEquals(emptyList(), result.edits)
}
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*cleanupRemovesUnusedSelectiveImportItems' --tests '*LanguageFormatterTest*cleanupPreservesUsedFunctionStructAndClassImports' --tests '*LanguageFormatterTest*cleanupReturnsNoEditsWhenAnalysisHasErrors'
```

Expected: cleanup removal test fails because cleanup currently delegates to format only.

- [ ] **Step 3: Add semantic import metadata**

In `FrontendPipelines.kt`, add:

```kotlin
data class ImportItemBindingInfo(
    val sourceText: String,
    val itemName: String,
    val itemRange: SourceRange,
    val symbol: SymbolInfo,
)
```

Add to `SemanticResult` in `LanguageFrontend.kt`:

```kotlin
val importItemBindings: List<ImportItemBindingInfo>,
```

Add a mutable list in `SemanticAnalyzer`:

```kotlin
private val importItemBindings = mutableListOf<ImportItemBindingInfo>()
```

Include it in the returned `SemanticResult`.

When registering selected built-in functions, selected records, selected functions, and selected classes, append an `ImportItemBindingInfo` with:

```kotlin
ImportItemBindingInfo(
    sourceText = sourceDisplayText,
    itemName = item.name,
    itemRange = item.range,
    symbol = binding.symbol,
)
```

Pass `sourceDisplayText` into `registerSelectedRecord`, `registerSelectedFunction`, and `registerSelectedClass` from `registerFileSelectiveImport`. Use `source.name` for built-ins and `"\"${source.path}\""` for file imports.

- [ ] **Step 4: Implement cleanup filtering**

In `LanguageFormatter.cleanupDocument`, parse and analyze:

```kotlin
val parsed = parser.parse(name, source)
if (parsed.syntaxDiagnostics.any { it.severity == FrontendSeverity.ERROR }) return cannotFormat()
val analysis = LanguageFrontend().analyze(name, source, loader)
if (analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR }) return FormatResult(emptyList())
```

Add a cleanup path that removes unused selective items before rendering imports:

- Build `referencedSymbols = analysis.references.map { it.target }.toSet()`.
- For each `ImportItemBindingInfo`, mark unused if its `symbol` is not in `referencedSymbols`.
- Remove only those selective items from normalized imports.
- If a selective import group becomes empty, remove the import.
- Keep namespace aliases unless a reliable alias-reference check is already available. If not available, keep aliases for MVP.

- [ ] **Step 5: Run targeted cleanup tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*cleanupRemovesUnusedSelectiveImportItems' --tests '*LanguageFormatterTest*cleanupPreservesUsedFunctionStructAndClassImports' --tests '*LanguageFormatterTest*cleanupReturnsNoEditsWhenAnalysisHasErrors'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run compiler tests**

Run:

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

## Task 7: IDE facade, device host, and workbench wiring

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`

- [ ] **Step 1: Add failing IDE facade tests**

Append:

```kotlin
@Test
fun languageIdeFormatsDocument() {
    val ide = LanguageIde()
    val source = "fun main(){terminal::println(\"hi\");}"

    val result = ide.formatDocument("main.ck", source)

    assertEquals("fun main() {\n    terminal::println(\"hi\");\n}\n", applySingleEdit(source, result))
}

@Test
fun languageIdeCleansDocument() {
    val ide = LanguageIde()
    val source = "import terminal { clear, println }; fun main(){println(\"hi\");}"

    val result = ide.cleanupDocument("main.ck", source)

    assertEquals("import terminal { println };\n\nfun main() {\n    println(\"hi\");\n}\n", applySingleEdit(source, result))
}
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*languageIdeFormatsDocument' --tests '*LanguageFormatterTest*languageIdeCleansDocument'
```

Expected: compilation fails because `LanguageIde` does not expose formatting methods.

- [ ] **Step 3: Extend compiler IDE facade**

In `IdeFacade`, add:

```kotlin
fun formatDocument(
    name: String,
    source: String,
): FormatResult

fun cleanupDocument(
    name: String,
    source: String,
    loader: SourceLoader = NoOpSourceLoader,
): FormatResult
```

In `LanguageIde`, add a formatter dependency:

```kotlin
private val formatter: LanguageFormatter = LanguageFormatter(parser),
```

Then implement:

```kotlin
override fun formatDocument(name: String, source: String): FormatResult =
    formatter.formatDocument(name, source)

override fun cleanupDocument(
    name: String,
    source: String,
    loader: SourceLoader,
): FormatResult = formatter.cleanupDocument(name, source, loader)
```

Adjust constructor parameter order if needed so existing call sites keep compiling.

- [ ] **Step 4: Add runtime request/response models**

In `DeviceIdeHost.kt`, add:

```kotlin
data class DeviceFormatRequest(
    val path: String,
)

data class DeviceFormatResponse(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class DeviceCleanupRequest(
    val path: String,
)

data class DeviceCleanupResponse(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
)
```

Extend `DeviceIdeHost`:

```kotlin
fun format(
    deviceId: Int,
    request: DeviceFormatRequest,
): DeviceFormatResponse

fun cleanup(
    deviceId: Int,
    request: DeviceCleanupRequest,
): DeviceCleanupResponse
```

- [ ] **Step 5: Wire `WorkspaceDeviceIdeHost`**

In `WorkspaceDeviceIdeHost`, implement `format` and `cleanup` like existing `complete`, `hover`, and `definition`: read the workspace document, call `ide(deviceId).formatDocument(...)` or `.cleanupDocument(...)`, and wrap edits/diagnostics into response objects. For cleanup, pass the workspace source loader used by the existing IDE instance so file imports can be analyzed.

- [ ] **Step 6: Wire workbench gateway/contracts**

Follow the existing `complete` gateway pattern in `WorkbenchContracts.kt` and `WorkbenchGateways.kt`. Add methods with the smallest surface that returns `FormatResult` or response models already defined in runtime. Keep naming consistent with existing workbench IDE methods.

- [ ] **Step 7: Run targeted tests**

Run:

```bash
./gradlew :compiler:test --tests '*LanguageFormatterTest*languageIdeFormatsDocument' --tests '*LanguageFormatterTest*languageIdeCleansDocument'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run full tests**

Run:

```bash
./gradlew :compiler:test
./gradlew test
```

Expected: both commands report `BUILD SUCCESSFUL`.

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

## Task 8: Documentation, final verification, and tag

**Files:**
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Update language docs**

Add a section after Imports or near IDE behavior:

```markdown
## Formatting and Cleanup

CKL supports backend Format Document and Cleanup Document operations.

Format Document canonicalizes valid CKL source:

- 4-space indentation,
- sorted and merged imports,
- canonical spacing around operators and punctuation,
- canonical block layout,
- preserved comments.

Cleanup Document performs the same formatting and additionally removes unused selective import items when semantic analysis proves they are unused. If the source has syntax or semantic errors, cleanup returns no edits.
```

- [ ] **Step 2: Run stale behavior search**

Run:

```bash
grep -rnE 'Format Document|Cleanup Document|formatter|cleanup' docs modules/compiler/src/test --include='*.md' --include='*.kt' || true
```

Expected: new docs and tests mention the behavior; no contradictory docs say CKL lacks formatting.

- [ ] **Step 3: Run final verification**

Run:

```bash
./gradlew :compiler:test
./gradlew test
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 4: Check clean status**

Run:

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

Run:

```bash
./gradlew :compiler:test
./gradlew test
git status --short
git tag -f ckl-format-cleanup-complete
```

Expected: tests pass, status is clean, and tag points at current `HEAD`.
