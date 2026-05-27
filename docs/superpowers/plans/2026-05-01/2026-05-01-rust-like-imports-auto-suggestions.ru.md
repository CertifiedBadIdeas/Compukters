# Implementation Plan для Rust-like Imports и Auto Suggestions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить explicit selective imports для built-in namespaces и `.ck` files, удалить flat file imports и дать auto-import completions с source label справа.

**Architecture:** Imports моделируются как `source + mode`, selective imports резолвятся в обычные visible bindings, namespace imports остаются для `::` доступа. Completion получает metadata и text edits, чтобы workbench мог вставить выбранное имя и atomically обновить import group. Lightweight workspace source index даёт importable user-file symbols для IDE, не меняя deterministic compiler semantics.

**Tech Stack:** Kotlin, Gradle, CKL compiler frontend, CKL runtime tests, workbench UI/completion flow.

---

## File structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt`: заменить flat import data class на source/mode/item model.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: parser, semantic import registration, selected built-in bindings, selected file bindings, flat import diagnostics.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`: обходить selective file imports при построении project parse cache.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`: добавить source-index abstractions и map-backed implementation.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt`: реализовать workspace source indexing для device workspaces.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`: добавить completion source-label и text-edit metadata.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`: генерировать importable completions и import edits.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`: поддержать import-group contexts и import insertion helpers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/IdePresentationSupport.kt`: заполнять source labels для обычных symbol completions при наличии данных.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`: применять completion edits atomically и сохранять cursor placement.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt`: рисовать completion label слева и source label справа.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt` и `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`: прокинуть workspace source index в IDE completion paths.
- Modify tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/`, `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/`, and `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/`.
- Modify `docs/LANGUAGE.md`: заменить flat import docs на selective import docs.

### Task 1: Import AST and parser tests

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing parser/model tests**

Add these tests to `UserFileImportsTest`:

```kotlin
@Test
fun parsesSelectiveFileImport() {
    val loader =
        MapSourceLoader(
            mapOf(
                "math.ck" to "fun add(): Int { return 1; } struct Vec2 { x: Int, y: Int }",
                "main.ck" to "import \"math.ck\" { add, Vec2 }; fun main() { }",
            ),
        )

    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun rejectsFlatFileImport() {
    val loader = MapSourceLoader(mapOf("math.ck" to "fun add(): Int { return 1; }"))

    val artifact = frontend.compile("main.ck", "import \"math.ck\"; fun main() { }", loader)

    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Use `import \"math.ck\" { name }` or `import \"math.ck\" as alias`")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun parsesSelectiveBuiltinImport() {
    val artifact = frontend.compile("main.ck", "import terminal { println }; fun main() { println(\"hi\"); }")

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun rejectsBareBuiltinImport() {
    val artifact = frontend.compile("main.ck", "import terminal; fun main() { terminal::println(\"hi\"); }")

    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Use `import terminal { name }`")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run parser/model tests to verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*"
```

Expected: FAIL because `import terminal { ... }`, `import "file.ck" { ... }`, and flat-import rejection are not implemented.

- [ ] **Step 3: Replace import model**

Replace `ImportDeclaration.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.lang.api

sealed interface ImportSource {
    val range: SourceRange

    data class FilePath(
        val path: String,
        override val range: SourceRange,
    ) : ImportSource

    data class BuiltinNamespace(
        val name: String,
        override val range: SourceRange,
    ) : ImportSource
}

sealed interface ImportMode {
    data class Namespace(
        val alias: String,
        val aliasRange: SourceRange,
    ) : ImportMode

    data class Selective(
        val items: List<ImportItem>,
        val range: SourceRange,
    ) : ImportMode

    data class Invalid(
        val message: String,
        val range: SourceRange,
    ) : ImportMode
}

data class ImportItem(
    val name: String,
    val range: SourceRange,
)

data class ImportDeclaration(
    val source: ImportSource,
    val mode: ImportMode,
    val range: SourceRange,
)
```

- [ ] **Step 4: Update parser import parsing**

In `LanguageFrontend.kt`, replace `parseImport()` with source/mode parsing using existing token helpers:

```kotlin
private fun parseImport(): ImportDeclaration? {
    val importToken = consume(TokenKind.IMPORT, "Expected `import`.") ?: return null
    val source =
        when {
            check(TokenKind.STRING) -> {
                val pathToken = advance()
                ImportSource.FilePath(pathToken.lexeme.trim('"'), pathToken.range)
            }
            check(TokenKind.IDENTIFIER) -> {
                val nameToken = advance()
                ImportSource.BuiltinNamespace(nameToken.lexeme, nameToken.range)
            }
            else -> {
                error(peek(), "Expected import source.")
                return null
            }
        }

    val mode =
        when {
            match(TokenKind.AS) -> {
                val alias = consume(TokenKind.IDENTIFIER, "Expected import alias after `as`.") ?: return null
                ImportMode.Namespace(alias.lexeme, alias.range)
            }
            match(TokenKind.LBRACE) -> parseSelectiveImportMode()
            else -> {
                ImportMode.Invalid(
                    message =
                        when (source) {
                            is ImportSource.FilePath -> "Use `import \"${source.path}\" { name }` or `import \"${source.path}\" as alias`."
                            is ImportSource.BuiltinNamespace -> "Use `import ${source.name} { name }`."
                        },
                    range = source.range,
                )
            }
        }
    val semicolon = consume(TokenKind.SEMICOLON, "Expected `;` after import.") ?: return null
    return ImportDeclaration(source, mode, SourceRange(importToken.range.start, semicolon.range.end))
}

private fun parseSelectiveImportMode(): ImportMode.Selective {
    val items = mutableListOf<ImportItem>()
    val start = previous().range.start
    if (!check(TokenKind.RBRACE)) {
        do {
            val item = consume(TokenKind.IDENTIFIER, "Expected imported name.") ?: break
            items += ImportItem(item.lexeme, item.range)
        } while (match(TokenKind.COMMA))
    }
    val end = consume(TokenKind.RBRACE, "Expected `}` after import list.")?.range?.end ?: previous().range.end
    return ImportMode.Selective(items, SourceRange(start, end))
}
```

Add imports for `ImportSource`, `ImportMode`, and `ImportItem` at the top of `LanguageFrontend.kt`.

- [ ] **Step 5: Run tests to see semantic failures**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*"
```

Expected: parser/model rejection tests pass before committing. If the import model change breaks existing semantic code, update `registerImports()` in the same task so namespace file imports still work and `ImportMode.Invalid` emits diagnostics; do not commit a non-compiling checkpoint.

- [ ] **Step 6: Commit parser/model work**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/ImportDeclaration.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): parse selective import declarations"
```

### Task 2: Selective built-in imports

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`

- [ ] **Step 1: Add focused built-in semantic tests**

Add to `UserFileImportsTest`:

```kotlin
@Test
fun selectiveBuiltinImportDoesNotExposeOtherMembers() {
    val artifact = frontend.compile("main.ck", "import terminal { println }; fun main() { clear(); }")

    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Unknown function `clear`")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun selectiveBuiltinImportConflictsWithLocalFunction() {
    val artifact = frontend.compile("main.ck", "import terminal { println }; fun println() { }")

    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration of `println`")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run focused tests to verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveBuiltin*"
```

Expected: FAIL because selective built-in imports are not registered.

- [ ] **Step 3: Implement selected built-in binding registration**

In `SemanticAnalyzer.registerImports()`, branch on `ImportSource.BuiltinNamespace`. Add helpers:

```kotlin
private fun registerBuiltinSelectiveImport(
    declaration: ImportDeclaration,
    source: ImportSource.BuiltinNamespace,
    mode: ImportMode.Selective,
) {
    val module = registry.module(source.name)
    if (module == null) {
        diagnostics += FrontendDiagnostic("Unknown namespace `${source.name}`.", source.range)
        return
    }
    val seenItems = mutableSetOf<String>()
    mode.items.forEach { item ->
        if (!seenItems.add(item.name)) {
            diagnostics += FrontendDiagnostic("Duplicate import of `${item.name}`.", item.range)
            return@forEach
        }
        val function = module.functions.firstOrNull { it.name == item.name }
        if (function == null) {
            diagnostics += FrontendDiagnostic("Namespace `${source.name}` has no member `${item.name}`.", item.range)
            return@forEach
        }
        if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) || userFunctionsByName.containsKey(item.name) || userRecordsByName.containsKey(item.name)) {
            diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
            return@forEach
        }
        val parameterTypes = function.parameterTypes.map { TypeRef(it) }
        val returnType = TypeRef(function.returnType)
        val symbol =
            SymbolInfo(
                name = item.name,
                kind = SymbolKind.BUILTIN_FUNCTION,
                range = item.range,
                detail = "${source.name}::${function.name}(${parameterTypes.joinToString { it.displayName }}): ${returnType.displayName}",
                documentation = function.documentation,
            )
        symbols += symbol
        userFunctionsByName[item.name] =
            FunctionBinding(
                symbol = symbol,
                declaration = null,
                parameterTypes = parameterTypes,
                returnType = returnType,
                builtinModuleName = source.name,
            )
    }
}
```

Update `registerImports()` so `ImportMode.Invalid` emits its message and `ImportMode.Namespace` is rejected for built-in sources with `Use \`import terminal { name }\`.`

- [ ] **Step 4: Run built-in tests to verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveBuiltin*" --tests "*UserFileImportsTest*parsesSelectiveBuiltinImport*" --tests "*UserFileImportsTest*rejectsBareBuiltinImport*"
```

Expected: PASS.

- [ ] **Step 5: Commit built-in selective imports**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt
git commit -m "feat(compiler): resolve selective builtin imports"
```

### Task 3: Selective user-file imports and flat import removal

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`

- [ ] **Step 1: Replace flat-import tests with selective-import tests**

In `UserFileImportsTest`, replace tests that use `import "a.ck";` with selective equivalents. Add:

```kotlin
@Test
fun selectiveFileImportExposesOnlySelectedFunction() {
    val loader =
        MapSourceLoader(
            mapOf(
                "math.ck" to "fun add(): Int { return 1; } fun hidden(): Int { return 2; }",
                "main.ck" to "import \"math.ck\" { add }; fun main() { terminal::println(\"v=\" + add()); hidden(); }",
            ),
        )

    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

    assertTrue(
        artifact.analysis.diagnostics.any {
            it.severity == FrontendSeverity.ERROR && it.message.contains("Unknown function `hidden`")
        },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}

@Test
fun selectiveFileImportExposesStructTypeAndConstructor() {
    val loader =
        MapSourceLoader(
            mapOf(
                "math.ck" to "struct Vec2 { x: Int, y: Int } fun make(): Vec2 { return Vec2 { x: 1, y: 2 }; }",
                "main.ck" to "import \"math.ck\" { Vec2, make }; fun main() { val v: Vec2 = make(); terminal::println(\"x=\" + v.x); }",
            ),
        )

    val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*selectiveFile*"
```

Expected: FAIL because file selected members are not registered and `FrontendPipelines` does not traverse selective file imports.

- [ ] **Step 3: Update import traversal in `FrontendPipelines.kt`**

In `analyzeProject()`, replace import traversal that reads `declaration.path` with file-source handling:

```kotlin
source.program.imports.forEach { declaration ->
    val fileSource = declaration.source as? ImportSource.FilePath ?: return@forEach
    if (!fileSource.path.endsWith(".ck")) {
        diagnostics += FrontendDiagnostic("Import path must end with `.ck`.", fileSource.range)
        return@forEach
    }
    val canonical = loader.resolve(source.name, fileSource.path)
    if (canonical == null) {
        diagnostics += FrontendDiagnostic("Unable to resolve import `${fileSource.path}`.", fileSource.range)
        return@forEach
    }
    parseRecursively(canonical)
}
```

Keep built-in imports out of the loader path.

- [ ] **Step 4: Implement selected file member registration**

In `SemanticAnalyzer`, replace `registerFlatImport()` calls with `registerFileSelectiveImport()` and keep `registerImportAlias()` for `ImportMode.Namespace` file imports:

```kotlin
private fun registerFileSelectiveImport(
    declaration: ImportDeclaration,
    source: ImportSource.FilePath,
    mode: ImportMode.Selective,
    exports: ModuleExports,
) {
    val seenItems = mutableSetOf<String>()
    mode.items.forEach { item ->
        if (!seenItems.add(item.name)) {
            diagnostics += FrontendDiagnostic("Duplicate import of `${item.name}`.", item.range)
            return@forEach
        }
        val struct = exports.structs[item.name]
        val function = exports.functions[item.name]
        if (struct == null && function == null) {
            diagnostics += FrontendDiagnostic("File `${source.path}` has no export `${item.name}`.", item.range)
            return@forEach
        }
        if (struct != null) {
            registerSelectedRecord(item, struct, exports)
        }
        if (function != null) {
            registerSelectedFunction(item, function, exports)
        }
    }
}

private fun registerSelectedRecord(
    item: ImportItem,
    struct: StructDeclaration,
    exports: ModuleExports,
) {
    if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) || typeNames.containsKey(item.name) || userRecordsByName.containsKey(item.name)) {
        diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
        return
    }
    val binding = recordBindingForExport(item.name, struct, exports, qualifier = null, item.range)
    typeNames[item.name] = TypeRef(item.name)
    userRecordsByName[item.name] = binding
    symbols += binding.symbol
}

private fun registerSelectedFunction(
    item: ImportItem,
    function: FunctionDeclaration,
    exports: ModuleExports,
) {
    if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) || userFunctionsByName.containsKey(item.name) || userRecordsByName.containsKey(item.name)) {
        diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
        return
    }
    val binding = functionBindingForExport(item.name, function, exports, qualifier = null, item.range)
    userFunctionsByName[item.name] = binding
    symbols += binding.symbol
}
```

- [ ] **Step 5: Run compiler import tests to verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsTest*"
```

Expected: PASS after migrating old flat-import assertions.

- [ ] **Step 6: Run runtime import tests**

Run:

```bash
./gradlew :compiler:test --tests "*UserFileImportsRuntimeTest*"
```

Expected: PASS after every runtime test source string uses `import "file.ck" { name };` or `import "file.ck" as alias;` instead of flat `import "file.ck";`.

- [ ] **Step 7: Commit selective user-file imports**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/UserFileImportsTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt
git commit -m "feat(compiler): resolve selective file imports"
```

### Task 4: Completion metadata and workbench multi-edit application

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Add failing workbench completion-edit test**

Add to `WorkbenchStoreTest`:

```kotlin
@Test
fun completionAppliesAdditionalTextEditsBeforeMainInsert() {
    val ide = FakeWorkbenchIdeFacade()
    ide.nextManualCompletions =
        listOf(
            CompletionItem(
                label = "println",
                detail = "terminal::println(text: String): Unit",
                kind = CompletionItemKind.FUNCTION,
                insertText = "println()",
                cursorOffset = "println(".length,
                sourceNamespace = "terminal",
                additionalTextEdits = listOf(TextEdit(0, 0, "import terminal { println };\n")),
            ),
        )
    val store = newStore(ideFacade = ide)
    store.openDocument(DeviceWorkspaceDocument("main.ck", "fun main() { pri }", version = 0))
    store.setCursorForTest(line = 0, column = "fun main() { pri".length)

    store.openCompletion()
    store.applyCompletion()

    assertEquals("import terminal { println };\nfun main() { println() }", store.state.editor.text)
    assertEquals(1, store.state.editor.cursorLine)
    assertEquals("fun main() { println(".length, store.state.editor.cursorColumn)
}
```

Add this mutable completion list to `FakeWorkbenchIdeFacade`:

```kotlin
var nextManualCompletions: List<CompletionItem> = listOf(CompletionItem(label = "manual", detail = "", kind = CompletionItemKind.KEYWORD))
```

Return `nextManualCompletions` from `complete(...)`.

- [ ] **Step 2: Run workbench test to verify RED**

Run:

```bash
./gradlew :core:test --tests "*WorkbenchStoreTest*completionAppliesAdditionalTextEditsBeforeMainInsert*"
```

Expected: FAIL because `TextEdit`, `sourceNamespace`, and `additionalTextEdits` do not exist.

- [ ] **Step 3: Extend completion API**

In `DeviceIdeHost.kt`, add:

```kotlin
data class TextEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
)
```

Extend `CompletionItem`:

```kotlin
data class CompletionItem(
    val label: String,
    val detail: String,
    val kind: CompletionItemKind,
    val documentation: String? = null,
    val insertText: String? = null,
    val cursorOffset: Int? = null,
    val sourceNamespace: String? = null,
    val additionalTextEdits: List<TextEdit> = emptyList(),
)
```

- [ ] **Step 4: Apply additional edits atomically enough for current CRDT path**

In `WorkbenchStore.applyCompletion()`, build all edits before mutating state. Apply `additionalTextEdits` sorted by descending `startOffset`, then the prefix replacement. Recompute cursor from inserted main edit:

```kotlin
val importEdits = item.additionalTextEdits.sortedByDescending { it.startOffset }
importEdits.forEach { edit ->
    if (edit.endOffset > edit.startOffset) applyLocalEdit(LocalEdit.Delete(edit.startOffset, edit.endOffset - edit.startOffset))
    if (edit.replacement.isNotEmpty()) applyLocalEdit(LocalEdit.Insert(edit.startOffset, edit.replacement))
}
val shiftedPrefixFlat = prefixFlat + importEdits.sumOf { it.replacement.length - (it.endOffset - it.startOffset) }
if (deletedLength > 0) applyLocalEdit(LocalEdit.Delete(shiftedPrefixFlat, deletedLength))
if (effectiveInsert.isNotEmpty()) applyLocalEdit(LocalEdit.Insert(shiftedPrefixFlat, effectiveInsert))
val finalCursorFlat = (shiftedPrefixFlat + effectiveCursorOffset).coerceIn(0, state.editor.text.length)
```

Keep the existing duplicate-parentheses suppression and final state clearing.

- [ ] **Step 5: Render right-side source label**

In `WorkbenchUiBuilder.kt`, pass `source = item.sourceNamespace` into `completionRow`. Change row signature and render:

```kotlin
private fun UiScope.completionRow(
    width: Int,
    label: String,
    source: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    box(/* existing modifier */) {
        text(modifier = Modifier.align(UiAlignment.Start), color = TEXT_LIGHT, text = value { label })
        if (!source.isNullOrBlank()) {
            text(modifier = Modifier.align(UiAlignment.End), color = TEXT_DIM, text = value { source })
        }
    }
}
```

- [ ] **Step 6: Run workbench tests to verify GREEN**

Run:

```bash
./gradlew :core:test --tests "*WorkbenchStoreTest*completionAppliesAdditionalTextEditsBeforeMainInsert*"
```

Expected: PASS.

- [ ] **Step 7: Commit completion edit API**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt
git commit -m "feat(workbench): apply completion import edits"
```

### Task 5: Built-in auto-import completions

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Add failing IDE completion tests**

Add to `LanguageIdeTest`:

```kotlin
@Test
fun suggestsBuiltinMemberWithNamespaceAndImportEdit() {
    val source = "fun main() { pri }"
    val cursor = lineAndColumnOf(source, "pri") + 3

    val items = ide.complete("main.ck", source, cursor.first, cursor.second)
    val println = items.single { it.label == "println" && it.sourceNamespace == "terminal" }

    assertEquals("println()", println.insertText)
    assertEquals("println(".length, println.cursorOffset)
    assertEquals(listOf(TextEdit(0, 0, "import terminal { println };\n")), println.additionalTextEdits)
}

@Test
fun updatesExistingBuiltinImportGroupInCompletionEdit() {
    val source = "import terminal { clear };\nfun main() { pri }"
    val cursor = lineAndColumnOf(source, "pri") + 3

    val items = ide.complete("main.ck", source, cursor.first, cursor.second)
    val println = items.single { it.label == "println" && it.sourceNamespace == "terminal" }

    assertEquals(listOf(TextEdit("import terminal { ".length, "import terminal { clear".length, "clear, println")), println.additionalTextEdits)
}
```

Add imports for `TextEdit`.

- [ ] **Step 2: Run IDE tests to verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageIdeTest*suggestsBuiltinMemberWithNamespaceAndImportEdit*" --tests "*LanguageIdeTest*updatesExistingBuiltinImportGroupInCompletionEdit*"
```

Expected: FAIL because importable built-in completions are not produced.

- [ ] **Step 3: Add import edit helpers**

In `SourceTextSupport.kt`, add a simple import-group updater:

```kotlin
data class ImportGroupEditRequest(
    val sourceText: String,
    val sourceSyntax: String,
    val importedName: String,
)

fun importGroupEdit(request: ImportGroupEditRequest): TextEdit {
    val escaped = Regex.escape(request.sourceSyntax)
    val regex = Regex("(?m)^import\\s+$escaped\\s*\\{([^}]*)}\\s*;")
    val match = regex.find(request.sourceText)
    if (match == null) {
        return TextEdit(0, 0, "import ${request.sourceSyntax} { ${request.importedName} };\n")
    }
    val group = match.groups[1]!!
    val names =
        group.value
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
    if (request.importedName !in names) names += request.importedName
    names.sort()
    return TextEdit(group.range.first, group.range.last + 1, names.joinToString(", "))
}
```

Import `TextEdit` from runtime.

- [ ] **Step 4: Produce built-in importable candidates**

In `LanguageIde.completeFromAnalysis()`, after visible symbols and before keywords, add candidates from `registry.modules.flatMap { module.functions }` when:

- function name starts with prefix;
- no visible symbol with the same label exists;
- source is not already selected with that name.

Use:

```kotlin
CompletionItem(
    label = function.name,
    detail = "${module.name}::${function.name}(${function.parameterTypes.joinToString()}): ${function.returnType}",
    kind = CompletionItemKind.FUNCTION,
    documentation = function.documentation,
    insertText = "${function.name}()",
    cursorOffset = "${function.name}(".length,
    sourceNamespace = module.name,
    additionalTextEdits = listOf(SourceTextSupport.importGroupEdit(ImportGroupEditRequest(source, module.name, function.name))),
)
```

Filter out candidates that conflict with visible symbols from `analysis.visibleSymbolsAt(offset)`.

- [ ] **Step 5: Run IDE tests to verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests "*LanguageIdeTest*suggestsBuiltinMemberWithNamespaceAndImportEdit*" --tests "*LanguageIdeTest*updatesExistingBuiltinImportGroupInCompletionEdit*"
```

Expected: PASS.

- [ ] **Step 6: Commit built-in auto-import completion**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat(ide): suggest builtin auto imports"
```

### Task 6: Workspace source index and user-file auto-import completions

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt`

- [ ] **Step 1: Add failing map source index test**

Add to `SourceLoaderTest`:

```kotlin
@Test
fun mapSourceLoaderListsCkSources() {
    val loader = MapSourceLoader(mapOf("main.ck" to "", "lib/math.ck" to "", "notes.txt" to ""))

    assertEquals(listOf("lib/math.ck", "main.ck"), loader.listSources().sorted())
}
```

- [ ] **Step 2: Add failing user-file auto-import completion test**

Add to `LanguageIdeTest`:

```kotlin
@Test
fun suggestsUserFileFunctionWithPathAndImportEdit() {
    val loader = MapSourceLoader(mapOf("main.ck" to "fun main() { ad }", "lib/math.ck" to "fun add(): Int { return 1; }"))
    val ide = LanguageIde(sourceIndex = loader)
    val source = loader.read("main.ck")!!
    val cursor = lineAndColumnOf(source, "ad") + 2

    val items = ide.complete("main.ck", source, cursor.first, cursor.second)
    val add = items.single { it.label == "add" && it.sourceNamespace == "lib/math.ck" }

    assertEquals(listOf(TextEdit(0, 0, "import \"lib/math.ck\" { add };\n")), add.additionalTextEdits)
}
```

- [ ] **Step 3: Run source-index tests to verify RED**

Run:

```bash
./gradlew :compiler:test --tests "*SourceLoaderTest*mapSourceLoaderListsCkSources*" --tests "*LanguageIdeTest*suggestsUserFileFunctionWithPathAndImportEdit*"
```

Expected: FAIL because no source-index API exists.

- [ ] **Step 4: Add source-index API**

In `SourceLoader.kt`, add:

```kotlin
interface SourceIndex {
    fun listSources(): List<String>
    fun readIndexedSource(canonical: String): String?
}
```

Make `MapSourceLoader` implement `SourceIndex`:

```kotlin
override fun listSources(): List<String> = files.keys.filter { it.endsWith(".ck") }.sorted()

override fun readIndexedSource(canonical: String): String? = read(canonical)
```

Add `object EmptySourceIndex : SourceIndex` returning empty lists/null.

- [ ] **Step 5: Add parse-level export extraction**

In `LanguageIde.kt`, add constructor parameters:

```kotlin
private val parser: ParserFacade = DefaultParserFacade(),
private val sourceIndex: SourceIndex = EmptySourceIndex,
```

Add private candidate builder:

```kotlin
private fun userFileImportableCompletions(
    currentPath: String,
    source: String,
    prefix: String,
    hiddenNames: Set<String>,
): List<CompletionItem> =
    sourceIndex.listSources()
        .asSequence()
        .filter { it != currentPath }
        .flatMap { path ->
            val parsed = parser.parse(path, sourceIndex.readIndexedSource(path) ?: return@flatMap emptySequence())
            parsed.program.declarations.asSequence().mapNotNull { declaration ->
                val name =
                    when (declaration) {
                        is FunctionDeclaration -> declaration.name
                        is StructDeclaration -> declaration.name
                    }
                if (!name.startsWith(prefix) || name in hiddenNames) return@mapNotNull null
                CompletionItem(
                    label = name,
                    detail = when (declaration) {
                        is FunctionDeclaration -> "fun $name"
                        is StructDeclaration -> "struct $name"
                    },
                    kind = when (declaration) {
                        is FunctionDeclaration -> CompletionItemKind.FUNCTION
                        is StructDeclaration -> CompletionItemKind.TYPE
                    },
                    insertText = if (declaration is FunctionDeclaration) "$name()" else null,
                    cursorOffset = if (declaration is FunctionDeclaration) "$name(".length else null,
                    sourceNamespace = path,
                    additionalTextEdits = listOf(SourceTextSupport.importGroupEdit(ImportGroupEditRequest(source, "\"$path\"", name))),
                )
            }
        }
        .toList()
```

Add imports for `FunctionDeclaration`, `StructDeclaration`, `ParserFacade`, `DefaultParserFacade`, `SourceIndex`, and `EmptySourceIndex`.

- [ ] **Step 6: Implement device workspace source index**

In `DeviceWorkspaceSourceLoader.kt`, implement `SourceIndex`. Recursively list `.ck` files using `DeviceWorkspace.list(deviceId, path)` starting at `/` or the workspace root convention already used by this loader. Normalize returned paths to the same canonical format used by `resolve`.

Use this exact behavior:

```kotlin
override fun listSources(): List<String> = collectCkFiles("/").sorted()

override fun readIndexedSource(canonical: String): String? = read(canonical)
```

Implement `collectCkFiles(path)` recursively against `DeviceWorkspace.list(deviceId, path)`, keep paths ending in `.ck`, descend into directory entries, and skip entries that cannot be read.

- [ ] **Step 7: Run user-file completion tests to verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests "*SourceLoaderTest*mapSourceLoaderListsCkSources*" --tests "*LanguageIdeTest*suggestsUserFileFunctionWithPathAndImportEdit*"
```

Expected: PASS.

- [ ] **Step 8: Commit source-index completions**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoader.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspaceSourceLoader.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceLoaderTest.kt
git commit -m "feat(ide): suggest user-file auto imports"
```

### Task 7: Wire workspace-backed IDE services

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Test: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/LanguageWorkbenchIdeFacadeTest.kt`

- [ ] **Step 1: Add a failing workbench IDE facade source-index test**

Create `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/LanguageWorkbenchIdeFacadeTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.infrastructure.workbench

import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertTrue

class LanguageWorkbenchIdeFacadeTest {
    @Test
    fun completeUsesInjectedSourceIndexForUserFileAutoImports() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "main.ck" to "fun main() { ad }",
                    "lib/math.ck" to "fun add(): Int { return 1; }",
                ),
            )
        val facade =
            LanguageWorkbenchIdeFacade(
                catalogSource = StaticCatalogSource(LanguageFrontend().registry),
                sourceIndex = loader,
            )
        val source = loader.read("main.ck")!!

        val items = facade.complete("main.ck", source, line = 0, column = "fun main() { ad".length)

        assertTrue(items.any { it.label == "add" && it.sourceNamespace == "lib/math.ck" }, items.joinToString { "${it.label}:${it.sourceNamespace}" })
    }
}
```

Use an existing simple catalog source if the module already has one. If not, add this private helper at the bottom of the test file:

```kotlin
private class StaticCatalogSource(
    private val registry: ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry = registry
}
```

- [ ] **Step 2: Run facade test to verify RED**

Run:

```bash
./gradlew :v1_21_1-common:test --tests "*LanguageWorkbenchIdeFacadeTest*"
```

Expected: FAIL because `LanguageWorkbenchIdeFacade` has no `sourceIndex` constructor parameter.

- [ ] **Step 3: Replace singleton-only IDE usage for workspace completion**

In `WorkspaceDeviceIdeHost`, construct `LanguageIde(sourceIndex = DeviceWorkspaceSourceLoader(workspace, deviceId))` for per-device completion paths. Keep shared `LanguageServices.frontend` registry where possible.

- [ ] **Step 4: Pass source index into `LanguageWorkbenchIdeFacade`**

In `WorkbenchGateways.kt`, extend `LanguageWorkbenchIdeFacade` constructor with a `SourceIndex` dependency and create its `LanguageIde` with that index. Existing callers that lack workspace access should pass `EmptySourceIndex`.

- [ ] **Step 5: Run core and facade tests**

Run:

```bash
./gradlew :core:test :v1_21_1-common:test --tests "*LanguageWorkbenchIdeFacadeTest*"
```

Expected: PASS.

- [ ] **Step 6: Commit IDE wiring**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/LanguageWorkbenchIdeFacadeTest.kt
git commit -m "feat(workbench): wire workspace source index into IDE completions"
```

### Task 8: Documentation and stale flat-import cleanup

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: any `.ck` examples/tests that still use flat imports

- [ ] **Step 1: Update language docs**

In `docs/LANGUAGE.md`, replace import examples with:

```markdown
CKL programs may import selected names from other `.ck` files. The path is interpreted relative to the importing file and must end with `.ck`.

```ck
import "lib/math.ck" { add, Vec2 };  // selected names visible directly
import "lib/math.ck" as math;        // namespace access via `math::name`
import terminal { println };         // selected built-in member visible directly
```

`import "lib/math.ck";` is invalid. Use a selective import list or a namespace alias.
```

- [ ] **Step 2: Search for stale flat imports**

Run:

```bash
grep -rnE 'import "[^"]+\.ck" *;' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true
```

Expected: only intentional tests that assert rejection may remain.

- [ ] **Step 3: Run full tests**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit docs and cleanup**

Run:

```bash
git add docs/LANGUAGE.md modules
git commit -m "docs(language): document selective imports and auto suggestions"
```

### Task 9: Final verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run targeted compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run targeted core tests**

Run:

```bash
./gradlew :core:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run stale syntax checks**

Run:

```bash
(grep -rnE 'import "[^"]+\.ck" *;' . --include='*.ck' --include='*.kt' --include='*.md' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git || true) | grep -v 'rejectsFlatFileImport' || true
```

Expected: no stale flat import usages outside rejection tests or historical plan/spec docs.

- [ ] **Step 5: Tag completion**

Run:

```bash
git status --short
git tag -f rust-like-imports-auto-suggestions-complete
```

Expected: clean worktree before tagging.