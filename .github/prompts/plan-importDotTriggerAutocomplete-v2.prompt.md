## Plan: Import & Dot-Trigger Autocomplete

**TL;DR:** Four phases: parser recovery → import completions → keyword trailing space → auto-trigger. Approach 1: parser recovery + snapshot reuse.

---

### Phase 1: Parser Recovery

**Files:** LanguageFrontend.kt, FrontendPipelines.kt

- `parseProgram()`: on failed declaration → `synchronize()` instead of `return null`
- `synchronize()`: skip tokens until `;` / `FUN` / `IMPORT` / `STRUCT` / `EOF`; consume `;` if found
- `parseProgram()` always returns `Program` (non-nullable)
- `DefaultAnalyzerFacade.analyze()`: remove early return on null, always run `SemanticAnalyzer`
- `AnalyzedProgram.program` and `ParseResult.program` — type changes from `Program?` to `Program`

**Tests:**
- `import terminal;\nimport \nfun main(){}` → AST contains `import terminal` and `fun main`
- `import terminal;\n$$$\nimport system;` → AST contains both imports

---

### Phase 2: Import Autocomplete

**Files:** SourceTextSupport.kt, LanguageIde.kt, AnalyzedProgram.kt, FrontendPipelines.kt (IdeFacade)

**Step 2.1** — `SourceTextSupport.importPrefix(source, offset): String?`
- Regex `import\s+(\w*)$` applied to text before cursor
- Returns partial module name (possibly empty) if in import context, `null` otherwise

**Step 2.2** — `AnalyzedProgram.importedModuleNames: Set<String>`
- Computed from `program.imports.map { it.moduleName }.toSet()`

**Step 2.3** — `LanguageIde.completeFromAnalysis(analysis, source, line, column): List<CompletionItem>`
- Extracts all completion logic from current `complete()` into this method
- `complete()` calls `analyze()` then delegates to `completeFromAnalysis()`
- New first branch: if `importPrefix != null` →
  - `registry.modules` filtered by prefix, excluding `importedModuleNames`
  - Mapped to `CompletionItem(kind=MODULE)`

**Step 2.4** — `IdeFacade` interface gets `completeFromAnalysis()` method

**Tests:**
- `import |` → all 6 modules
- `import te|` → `terminal`
- `import terminal;\nimport |` → 5 modules (terminal excluded)

---

### Phase 3: CompletionItem insertText & Keyword Trailing Space

**Files:** CompletionItem (ComputerWorkspace.kt), LanguageIde.kt, WorkbenchEditorSupport.kt

**Step 3.1** — `CompletionItem` gets new field `val insertText: String? = null`

**Step 3.2** — Keyword completions set `insertText = "$label "`
- With trailing space: `fun`, `val`, `var`, `if`, `else`, `while`, `when`, `return`, `import`, `struct`
- Without trailing space: `true`, `false`, `null` (literals, not body-keywords)

**Step 3.3** — `EditorState.applyCompletion()` uses `item.insertText ?: item.label` instead of `item.label`

**Tests:**
- Keyword completion `import` → insertText is `"import "`
- Literal completion `true` → insertText is `null`

---

### Phase 4: Auto-Trigger on Dot and Import

**Files:** WorkbenchStore.kt, WorkbenchContracts.kt (WorkbenchIdeFacade), WorkbenchGateways.kt (LanguageWorkbenchIdeFacade)

**Step 4.1** — `WorkbenchIdeFacade` gets `completeFromLastAnalysis(path, source, line, column): List<CompletionItem>`
- Semantics: complete using the most recent analysis if path+source match, otherwise fallback to full analyze+complete

**Step 4.2** — `LanguageWorkbenchIdeFacade` caches last `AnalyzedProgram` (from `analyze()`) with path+source key
- `completeFromLastAnalysis()`: if cache hit → delegates to `LanguageIde.completeFromAnalysis()`; if miss → delegates to `LanguageIde.complete()`

**Step 4.3** — `WorkbenchStore.charTyped()` changes:
- After `refreshIde()`, check `ch == '.'` or `shouldTriggerImportCompletion(ch)`
- If match → call `openCompletionFromCurrentSnapshot()`

**Step 4.4** — `shouldTriggerImportCompletion(ch: Char): Boolean`
- Returns `true` when `ch == ' '` and text before cursor ends with `"import "`

**Step 4.5** — `openCompletionFromCurrentSnapshot()` (new private method)
- Calls `ideFacade.completeFromLastAnalysis(document.path, state.editor.text, cursorLine, cursorColumn)`
- Sets `completionItems` and `selectedCompletion = 0` in state

---

### Relevant Files

| File | Changes |
|------|---------|
| LanguageFrontend.kt | `parseProgram()` recovery, `synchronize()` |
| FrontendPipelines.kt | Remove null early-return in `analyze()`, add `completeFromAnalysis()` to `IdeFacade` |
| SourceTextSupport.kt | `importPrefix()` |
| LanguageIde.kt | Import branch in completions, `completeFromAnalysis()`, keyword insertText |
| AnalyzedProgram.kt | `importedModuleNames` property, `program` becomes non-nullable |
| ComputerWorkspace.kt | `CompletionItem.insertText` field |
| WorkbenchEditorSupport.kt | `applyCompletion()` uses `insertText` |
| WorkbenchStore.kt | Auto-trigger logic in `charTyped()`, `openCompletionFromCurrentSnapshot()` |
| WorkbenchContracts.kt | `WorkbenchIdeFacade.completeFromLastAnalysis()` |
| WorkbenchGateways.kt | `LanguageWorkbenchIdeFacade` caching + `completeFromLastAnalysis()` |
| LanguageIdeTest.kt | New tests for parser recovery, import completions, keyword insertText |

### Verification

1. `./gradlew :compiler:test` — existing tests pass
2. New parser recovery tests: incomplete import preserves previous imports
3. New import completion tests: all modules, prefix filtering, duplicate filtering
4. New keyword insertText test: verify trailing space
5. Manual in-game: `terminal.` → auto-popup with methods
6. Manual in-game: `import ` → auto-popup with filtered module list
7. Manual in-game: type `imp` → apply `import` → space auto-added → module list auto-shown

### Decisions

- Parser recovery via synchronize() — skip to next top-level boundary
- Import filtering via AST (program.imports), not regex
- Snapshot reuse: auto-triggers use cached analysis from refreshIde(), no double analyze()
- completeFromLastAnalysis() caches in the facade adapter, not in WorkbenchStore
- Keyword trailing space for all body-keywords; no space for literals (true/false/null)
- Auto-trigger on `.` (dot) and ` ` (space after `import`)
