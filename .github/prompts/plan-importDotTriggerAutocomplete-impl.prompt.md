# Import & Dot-Trigger Autocomplete — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add import autocomplete, dot/import auto-trigger, keyword trailing spaces, and parser recovery.

**Architecture:** Parser recovery lets the IDE work with incomplete code. Import completions use AST-based filtering of already-imported modules. Auto-triggers on `.` and `import ` reuse the analysis snapshot to avoid redundant work. A new `insertText` field on `CompletionItem` enables keyword trailing spaces.

**Tech Stack:** Kotlin, Gradle, kotlin.test

---

### File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt` | Modify | Parser: `parseProgram()` recovery, new `synchronize()` |
| `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt` | Modify | `ParsedSource.program` non-nullable, `DefaultAnalyzerFacade.analyze()` no early return, `IdeFacade.completeFromAnalysis()` |
| `compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt` | Modify | `program` non-nullable, new `importedModuleNames` property |
| `compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt` | Modify | New `importPrefix()` |
| `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt` | Modify | Import completion branch, `completeFromAnalysis()`, keyword `insertText` |
| `compiler/src/main/kotlin/ck/lang/runtime/ComputerWorkspace.kt` | Modify | `CompletionItem.insertText` field |
| `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchEditorSupport.kt` | Modify | `applyCompletion()` uses `insertText` |
| `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchContracts.kt` | Modify | `WorkbenchIdeFacade.completeFromLastAnalysis()` |
| `mod/src/main/kotlin/ck/mod/infrastructure/workbench/WorkbenchGateways.kt` | Modify | Cache + `completeFromLastAnalysis()` in adapter |
| `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt` | Modify | Auto-trigger in `charTyped()`, `openCompletionFromCurrentSnapshot()` |
| `compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt` | Modify | Tests for recovery, import completions, keyword insertText |

---

### Task 1: Parser Recovery

**Files:**
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt:1281-1316`
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt:37,104-135`
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt:48`
- Test: `compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1.1: Write failing tests for parser recovery**

Add these tests to `LanguageIdeTest.kt`:

```kotlin
@Test
fun recoversFromIncompleteImport() {
    val source = "import terminal;\nimport \nfun main() {\n    terminal.printLine(\"hi\");\n}"
    val snapshot = ide.analyze("recovery.ck", source)
    // Parser should recover: terminal import and main function should be in AST
    assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for incomplete import")
    // Completions should still work on the valid parts
    val completions = ide.complete("recovery.ck", source, 3, 13)
    assertTrue(completions.any { it.label == "printLine" }, "Should complete terminal members after recovery")
}

@Test
fun recoversFromGarbageToken() {
    val source = "import terminal;\n\$\$\$\nimport system;\nfun main() {}"
    val snapshot = ide.analyze("garbage.ck", source)
    assertTrue(snapshot.diagnostics.isNotEmpty(), "Should have diagnostics for garbage tokens")
    // Both imports should be recovered
    val completions = ide.complete("garbage.ck", source, 3, 15)
    assertTrue(completions.any { it.label == "main" }, "Should see main function after recovery")
}
```

- [ ] **Step 1.2: Run tests to verify they fail**

Run: `./gradlew :compiler:test --tests "ck.lang.frontend.LanguageIdeTest.recoversFromIncompleteImport" --tests "ck.lang.frontend.LanguageIdeTest.recoversFromGarbageToken" --no-daemon`

Expected: FAIL — parser returns `null` for incomplete programs, no recovery happens.

- [ ] **Step 1.3: Add `synchronize()` to Parser and update `parseProgram()`**

In `compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt`, replace the `parseProgram()` method and add `synchronize()`:

```kotlin
fun parseProgram(): Program {
    val imports = mutableListOf<ImportDeclaration>()
    val declarations = mutableListOf<TopLevelDeclaration>()
    while (!isAtEnd()) {
        when {
            match(TokenKind.IMPORT) -> {
                val imp = parseImport()
                if (imp != null) imports += imp else synchronize()
            }

            match(TokenKind.FUN) -> {
                val decl = parseFunction()
                if (decl != null) declarations += decl else synchronize()
            }

            match(TokenKind.STRUCT) -> {
                val decl = parseStruct()
                if (decl != null) declarations += decl else synchronize()
            }

            check(TokenKind.EOF) -> {
                break
            }

            else -> {
                diagnostics += FrontendDiagnostic("Expected a top-level declaration.", peek().range)
                synchronize()
            }
        }
    }
    return Program(imports, declarations, declarations.lastOrNull()?.range ?: imports.lastOrNull()?.range)
}

private fun synchronize() {
    while (!isAtEnd()) {
        if (match(TokenKind.SEMICOLON)) return
        if (check(TokenKind.FUN) || check(TokenKind.IMPORT) || check(TokenKind.STRUCT)) return
        advance()
    }
}
```

- [ ] **Step 1.4: Update `ParsedSource.program` to non-nullable**

In `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt`, change the `ParsedSource` data class:

```kotlin
data class ParsedSource(
    val name: String,
    val source: String,
    val tokens: List<Token>,
    val syntaxDiagnostics: List<FrontendDiagnostic>,
    val program: Program,
)
```

- [ ] **Step 1.5: Update `DefaultAnalyzerFacade.analyze()` — remove null early return**

In `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt`, replace the `analyze()` method:

```kotlin
override fun analyze(
    name: String,
    source: String,
): AnalyzedProgram {
    val parsed = parser.parse(name, source)
    val program = parsed.program

    val semantic = SemanticAnalyzer(registry, name).analyze(program)
    return AnalyzedProgram(
        name = parsed.name,
        source = parsed.source,
        tokens = parsed.tokens,
        program = program,
        diagnostics = parsed.syntaxDiagnostics + semantic.diagnostics,
        symbols = semantic.symbols,
        references = semantic.references,
        builtinModules = registry.modules,
        builtinGlobals = registry.globals,
    ).rememberSemantic(semantic)
}
```

- [ ] **Step 1.6: Update `AnalyzedProgram.program` to non-nullable**

In `compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt`, change the constructor parameter:

```kotlin
class AnalyzedProgram(
    val name: String,
    val source: String,
    val tokens: List<Token>,
    val program: Program,
    val diagnostics: List<FrontendDiagnostic>,
    val symbols: List<SymbolInfo>,
    val references: List<ReferenceInfo>,
    private val builtinModules: List<BuiltinModule>,
    private val builtinGlobals: List<BuiltinFunction>,
) {
```

- [ ] **Step 1.7: Update `DefaultCompilerFacade.compile()` — remove `program == null` check**

In `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt`, update the null check in `compile()`:

```kotlin
override fun compile(
    name: String,
    source: String,
): CompilationArtifact {
    val analysis = analyzer.analyze(name, source)
    val semantic = analysis.semantic
    if (semantic == null ||
        analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR }
    ) {
        return CompilationArtifact(module = null, analysis = analysis)
    }

    return CompilationArtifact(
        module = BytecodeCompiler(registry, semantic).compile(name),
        analysis = analysis,
    )
}
```

- [ ] **Step 1.8: Run all compiler tests to verify recovery works**

Run: `./gradlew :compiler:test --no-daemon`

Expected: ALL PASS — existing tests continue to work (valid programs parse identically), new recovery tests pass.

- [ ] **Step 1.9: Commit**

```bash
git add compiler/src/main/kotlin/ck/lang/frontend/LanguageFrontend.kt \
      compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt \
      compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt \
      compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: add parser recovery for IDE robustness"
```

---

### Task 2: Import Context Detection

**Files:**
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt`
- Test: `compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 2.1: Write failing test for importPrefix**

Add test to `LanguageIdeTest.kt`:

```kotlin
@Test
fun importPrefixDetectsImportContext() {
    // "import te" — cursor at end
    val prefix1 = SourceTextSupport.importPrefix("import te", 9)
    assertEquals("te", prefix1)

    // "import " — cursor right after space
    val prefix2 = SourceTextSupport.importPrefix("import ", 7)
    assertEquals("", prefix2)

    // "terminal." — not import context
    val prefix3 = SourceTextSupport.importPrefix("terminal.", 9)
    assertNull(prefix3)

    // "import terminal;\nimport sy" — second import
    val prefix4 = SourceTextSupport.importPrefix("import terminal;\nimport sy", 26)
    assertEquals("sy", prefix4)
}
```

Note: `SourceTextSupport` is `internal`, and the test is in the same package — this works.

- [ ] **Step 2.2: Run test to verify it fails**

Run: `./gradlew :compiler:test --tests "ck.lang.frontend.LanguageIdeTest.importPrefixDetectsImportContext" --no-daemon`

Expected: FAIL — `importPrefix` does not exist yet.

- [ ] **Step 2.3: Implement `importPrefix()` in SourceTextSupport**

In `compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt`, add the new regex and function:

```kotlin
internal object SourceTextSupport {
    private val identifierPrefixRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*$""")
    private val moduleMemberRegex = Regex("""([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z0-9_]*)$""")
    private val importPrefixRegex = Regex("""import\s+(\w*)$""")

    fun importPrefix(
        source: String,
        offset: Int,
    ): String? {
        val prefix = source.take(offset)
        val match = importPrefixRegex.find(prefix) ?: return null
        return match.groupValues[1]
    }

    // ... rest unchanged
}
```

- [ ] **Step 2.4: Run test to verify it passes**

Run: `./gradlew :compiler:test --tests "ck.lang.frontend.LanguageIdeTest.importPrefixDetectsImportContext" --no-daemon`

Expected: PASS

- [ ] **Step 2.5: Commit**

```bash
git add compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt \
      compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: add importPrefix() to SourceTextSupport"
```

---

### Task 3: Import Completion Branch + completeFromAnalysis

**Files:**
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt`
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt`
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt`
- Test: `compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 3.1: Write failing tests for import completions**

Add tests to `LanguageIdeTest.kt`:

```kotlin
@Test
fun completesImportModules() {
    // Empty prefix — should show all 6 modules
    val allModules = ide.complete("test.ck", "import ", 0, 7)
    val moduleLabels = allModules.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
    assertEquals(setOf("terminal", "filesystem", "system", "events", "process", "strings"), moduleLabels)
}

@Test
fun completesImportModulesWithPrefix() {
    // Prefix "te" — should match only terminal
    val filtered = ide.complete("test.ck", "import te", 0, 9)
    val moduleLabels = filtered.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }
    assertEquals(listOf("terminal"), moduleLabels)
}

@Test
fun completesImportModulesExcludingAlreadyImported() {
    // terminal already imported — should not appear in suggestions
    val source = "import terminal;\nimport "
    val completions = ide.complete("test.ck", source, 1, 7)
    val moduleLabels = completions.filter { it.kind == CompletionItemKind.MODULE }.map { it.label }.toSet()
    assertFalse(moduleLabels.contains("terminal"), "Should not suggest already-imported terminal")
    assertEquals(setOf("filesystem", "system", "events", "process", "strings"), moduleLabels)
}
```

Add the import for `CompletionItemKind` at the top of the test file:

```kotlin
import ck.lang.runtime.CompletionItemKind
```

- [ ] **Step 3.2: Run tests to verify they fail**

Run: `./gradlew :compiler:test --tests "ck.lang.frontend.LanguageIdeTest.completesImportModules" --tests "ck.lang.frontend.LanguageIdeTest.completesImportModulesWithPrefix" --tests "ck.lang.frontend.LanguageIdeTest.completesImportModulesExcludingAlreadyImported" --no-daemon`

Expected: FAIL — import completion logic does not exist yet.

- [ ] **Step 3.3: Add `importedModuleNames` to AnalyzedProgram**

In `compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt`, add this property in the class body (after `rememberSemantic`):

```kotlin
val importedModuleNames: Set<String>
    get() = program.imports.map { it.moduleName }.toSet()
```

- [ ] **Step 3.4: Add `completeFromAnalysis()` to `IdeFacade` interface**

In `compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt`, add the new method to `IdeFacade`:

```kotlin
interface IdeFacade {
    fun analyze(
        name: String,
        source: String,
    ): LanguageIde.IdeSnapshot

    fun complete(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun completeFromAnalysis(
        analysis: AnalyzedProgram,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun hover(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo?

    fun definition(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget?
}
```

- [ ] **Step 3.5: Refactor `LanguageIde` — extract `completeFromAnalysis()` and add import branch**

In `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt`, replace the `complete()` method and add `completeFromAnalysis()`:

```kotlin
override fun complete(
    name: String,
    source: String,
    line: Int,
    column: Int,
): List<CompletionItem> {
    val snapshot = analyze(name, source)
    return completeFromAnalysis(snapshot.analysis, source, line, column)
}

override fun completeFromAnalysis(
    analysis: AnalyzedProgram,
    source: String,
    line: Int,
    column: Int,
): List<CompletionItem> {
    val offset = SourceTextSupport.offsetAt(source, line, column)
    val importPrefix = SourceTextSupport.importPrefix(source, offset)
    if (importPrefix != null) {
        val alreadyImported = analysis.importedModuleNames
        return LanguageBuiltins.registry.modules
            .asSequence()
            .filter { it.name.startsWith(importPrefix) }
            .filter { it.name !in alreadyImported }
            .map {
                CompletionItem(
                    label = it.name,
                    detail = it.documentation,
                    kind = CompletionItemKind.MODULE,
                    documentation = it.documentation,
                )
            }.toList()
    }
    val prefix = SourceTextSupport.identifierPrefix(source, offset)
    val modulePrefix = SourceTextSupport.moduleMemberPrefix(source, offset)
    return if (modulePrefix != null) {
        analysis
            .moduleMembers(modulePrefix.first)
            .asSequence()
            .filter { it.name.startsWith(modulePrefix.second) }
            .map(IdePresentationSupport::completionItem)
            .distinctBy { it.kind to it.label }
            .toList()
    } else {
        buildList {
            addAll(
                analysis
                    .visibleSymbolsAt(offset)
                    .asSequence()
                    .filter { it.name.startsWith(prefix) }
                    .map(IdePresentationSupport::completionItem)
                    .toList(),
            )
            addAll(
                LanguageBuiltins.registry.builtinTypes
                    .asSequence()
                    .filter { it.name.startsWith(prefix) }
                    .map {
                        CompletionItem(
                            label = it.name,
                            detail = "struct ${it.name}",
                            kind = CompletionItemKind.TYPE,
                            documentation = it.documentation,
                        )
                    }.toList(),
            )
            addAll(
                KEYWORDS
                    .asSequence()
                    .filter { it.startsWith(prefix) }
                    .map {
                        CompletionItem(
                            label = it,
                            detail = "keyword",
                            kind = CompletionItemKind.KEYWORD,
                        )
                    }.toList(),
            )
        }.distinctBy { it.kind to it.label }
    }
}
```

- [ ] **Step 3.6: Run tests to verify they pass**

Run: `./gradlew :compiler:test --no-daemon`

Expected: ALL PASS — including the three new import completion tests.

- [ ] **Step 3.7: Commit**

```bash
git add compiler/src/main/kotlin/ck/lang/frontend/AnalyzedProgram.kt \
      compiler/src/main/kotlin/ck/lang/frontend/FrontendPipelines.kt \
      compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt \
      compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: add import autocomplete with AST-based duplicate filtering"
```

---

### Task 4: CompletionItem insertText & Keyword Trailing Space

**Files:**
- Modify: `compiler/src/main/kotlin/ck/lang/runtime/ComputerWorkspace.kt`
- Modify: `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt`
- Modify: `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchEditorSupport.kt`
- Test: `compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 4.1: Write failing tests for keyword insertText**

Add tests to `LanguageIdeTest.kt`:

```kotlin
@Test
fun keywordCompletionsHaveTrailingSpace() {
    val completions = ide.complete("test.ck", "imp", 0, 3)
    val importItem = completions.first { it.label == "import" }
    assertEquals("import ", importItem.insertText, "import keyword should have trailing space")
}

@Test
fun literalCompletionsHaveNoTrailingSpace() {
    val completions = ide.complete("test.ck", "tru", 0, 3)
    val trueItem = completions.first { it.label == "true" }
    assertNull(trueItem.insertText, "true literal should not have trailing space")
}
```

- [ ] **Step 4.2: Run tests to verify they fail**

Run: `./gradlew :compiler:test --tests "ck.lang.frontend.LanguageIdeTest.keywordCompletionsHaveTrailingSpace" --tests "ck.lang.frontend.LanguageIdeTest.literalCompletionsHaveNoTrailingSpace" --no-daemon`

Expected: FAIL — `insertText` field does not exist on `CompletionItem`.

- [ ] **Step 4.3: Add `insertText` field to CompletionItem**

In `compiler/src/main/kotlin/ck/lang/runtime/ComputerWorkspace.kt`, update the data class:

```kotlin
data class CompletionItem(
    val label: String,
    val detail: String,
    val kind: CompletionItemKind,
    val documentation: String? = null,
    val insertText: String? = null,
)
```

- [ ] **Step 4.4: Add trailing space to keyword completions in LanguageIde**

In `compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt`, update the KEYWORDS companion and the keyword completion block inside `completeFromAnalysis()`.

Replace the companion object:

```kotlin
private companion object {
    val KEYWORDS = listOf("fun", "val", "var", "if", "else", "when", "while", "return", "import", "struct", "true", "false", "null")
    val BODY_KEYWORDS = setOf("fun", "val", "var", "if", "else", "when", "while", "return", "import", "struct")
}
```

Replace the keyword completion block inside `completeFromAnalysis()`:

```kotlin
addAll(
    KEYWORDS
        .asSequence()
        .filter { it.startsWith(prefix) }
        .map {
            CompletionItem(
                label = it,
                detail = "keyword",
                kind = CompletionItemKind.KEYWORD,
                insertText = if (it in BODY_KEYWORDS) "$it " else null,
            )
        }.toList(),
)
```

- [ ] **Step 4.5: Update `applyCompletion()` to use insertText**

In `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchEditorSupport.kt`, update `applyCompletion()`:

```kotlin
internal fun EditorState.applyCompletion(item: CompletionItem): EditorState {
    val lines = lines().toMutableList()
    val line = lines[cursorLine]
    val prefixStart = line.findIdentifierStart(cursorColumn)
    val textToInsert = item.insertText ?: item.label
    lines[cursorLine] = line.substring(0, prefixStart) + textToInsert + line.substring(cursorColumn)
    return copy(
        text = lines.joinToString("\n"),
        dirty = true,
        cursorColumn = prefixStart + textToInsert.length,
        completionItems = emptyList(),
        selectedCompletion = 0,
    )
}
```

- [ ] **Step 4.6: Run all tests to verify**

Run: `./gradlew :compiler:test --no-daemon`

Expected: ALL PASS

- [ ] **Step 4.7: Commit**

```bash
git add compiler/src/main/kotlin/ck/lang/runtime/ComputerWorkspace.kt \
      compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt \
      mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchEditorSupport.kt \
      compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: add insertText to CompletionItem, keyword trailing spaces"
```

---

### Task 5: Auto-Trigger Infrastructure (WorkbenchIdeFacade + Adapter)

**Files:**
- Modify: `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchContracts.kt`
- Modify: `mod/src/main/kotlin/ck/mod/infrastructure/workbench/WorkbenchGateways.kt`

- [ ] **Step 5.1: Add `completeFromLastAnalysis()` to WorkbenchIdeFacade**

In `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchContracts.kt`, add the method to the interface:

```kotlin
interface WorkbenchIdeFacade {
    fun analyze(
        path: String,
        source: String,
    ): ComputerIdeSnapshot

    fun complete(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun completeFromLastAnalysis(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun hover(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo?

    fun definition(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget?
}
```

- [ ] **Step 5.2: Implement caching and `completeFromLastAnalysis()` in LanguageWorkbenchIdeFacade**

In `mod/src/main/kotlin/ck/mod/infrastructure/workbench/WorkbenchGateways.kt`, update the object. Add imports for `AnalyzedProgram`:

```kotlin
import ck.lang.frontend.AnalyzedProgram
```

Replace the full `LanguageWorkbenchIdeFacade` object:

```kotlin
object LanguageWorkbenchIdeFacade : WorkbenchIdeFacade {
    private val ide = LanguageServices.ide

    private var lastAnalysisPath: String? = null
    private var lastAnalysisSource: String? = null
    private var lastAnalysis: AnalyzedProgram? = null

    override fun analyze(
        path: String,
        source: String,
    ): ComputerIdeSnapshot =
        ide.analyze(path, source).let { snapshot ->
            lastAnalysisPath = path
            lastAnalysisSource = source
            lastAnalysis = snapshot.analysis
            ComputerIdeSnapshot(
                ComputerWorkspaceDocument(path = path, text = source, version = 0L),
                snapshot.diagnostics,
                snapshot.highlights,
            )
        }

    override fun complete(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> = ide.complete(path, source, line, column)

    override fun completeFromLastAnalysis(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val cached = lastAnalysis
        if (cached != null && lastAnalysisPath == path && lastAnalysisSource == source) {
            return ide.completeFromAnalysis(cached, source, line, column)
        }
        return ide.complete(path, source, line, column)
    }

    override fun hover(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo? = ide.hover(path, source, line, column)

    override fun definition(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? = ide.definition(path, source, line, column)
}
```

- [ ] **Step 5.3: Commit**

```bash
git add mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchContracts.kt \
      mod/src/main/kotlin/ck/mod/infrastructure/workbench/WorkbenchGateways.kt
git commit -m "feat: add completeFromLastAnalysis with analysis caching"
```

---

### Task 6: Auto-Trigger in WorkbenchStore

**Files:**
- Modify: `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt`

- [ ] **Step 6.1: Add auto-trigger logic to `charTyped()` and new helpers**

In `mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt`, replace `charTyped()` and add two new private methods after `refreshIde()`:

```kotlin
fun charTyped(
    ch: Char,
    visibleEditorLines: Int,
): Boolean {
    if (state.mode != WorkbenchMode.EDITOR) {
        return false
    }
    if (!Character.isISOControl(ch)) {
        _state.value = state.copy(editor = state.editor.insertText(ch.toString(), visibleEditorLines))
        refreshIde()
        if (ch == '.' || shouldTriggerImportCompletion(ch)) {
            openCompletionFromCurrentSnapshot()
        }
    }
    return true
}
```

Add these two private methods (after `refreshIde()`):

```kotlin
private fun shouldTriggerImportCompletion(ch: Char): Boolean {
    if (ch != ' ') return false
    val lines = state.editor.text.split('\n')
    val line = lines.getOrNull(state.editor.cursorLine) ?: return false
    val textBeforeCursor = line.substring(0, state.editor.cursorColumn)
    return textBeforeCursor.endsWith("import ")
}

private fun openCompletionFromCurrentSnapshot() {
    val document = state.openDocument ?: return
    val items = ideFacade.completeFromLastAnalysis(
        document.path,
        state.editor.text,
        state.editor.cursorLine,
        state.editor.cursorColumn,
    )
    if (items.isNotEmpty()) {
        _state.value = state.copy(
            editor = state.editor.copy(completionItems = items, selectedCompletion = 0),
        )
    }
}
```

- [ ] **Step 6.2: Run full compiler test suite to verify nothing broke**

Run: `./gradlew :compiler:test --no-daemon`

Expected: ALL PASS

- [ ] **Step 6.3: Commit**

```bash
git add mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt
git commit -m "feat: auto-trigger completions on dot and import space"
```

---

### Task 7: Final Verification

- [ ] **Step 7.1: Run full test suite**

Run: `./gradlew :compiler:test --no-daemon`

Expected: ALL PASS

- [ ] **Step 7.2: Verify no compile errors across both modules**

Run: `./gradlew :compiler:compileKotlin :mod:compileKotlin --no-daemon`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7.3: Manual verification checklist (in-game)**

1. Open computer workbench editor
2. Type `terminal.` — auto-popup should show: write, printLine, readLine, clear, setCursor
3. Type `import ` — auto-popup should show: all 6 modules
4. Type `import terminal;` then newline then `import ` — should show 5 modules (no terminal)
5. Type `imp` then Ctrl+Space, select `import` — should insert `import ` with trailing space and auto-show modules
6. Type `fun` via completion — should insert `fun ` with trailing space
