## Plan: Import & Dot-Trigger Autocomplete

**TL;DR:** Add two missing autocomplete features to the in-game computer editor: (1) suggest module names after `import `, and (2) auto-open the completion popup when typing `.` after a module name.

---

### Phase 1: Import Context Detection (compiler module)

**Step 1.1** — Add `importPrefix()` to [SourceTextSupport.kt](compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt)
- New function `importPrefix(source, offset): String?` — detects `import <partial>` pattern before cursor using a regex like `import\s+([A-Za-z_]\w*)?$`
- Returns the partial module name (possibly empty) if in import context, `null` otherwise
- Follows the same pattern as the existing `moduleMemberPrefix()`

**Step 1.2** — Handle import context in [LanguageIde.kt](compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt) `complete()`
- Add a **third branch** before `modulePrefix` check: if `importPrefix != null`, return `LanguageBuiltins.registry.modules` filtered by prefix, mapped to `CompletionItem(kind=MODULE)`
- All 6 modules offered: terminal, filesystem, system, events, process, strings

**Step 1.3** — Add tests in [LanguageIdeTest.kt](compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt)
- `import |` → all 6 modules; `import te|` → `terminal`; multi-import context

---

### Phase 2: Auto-Trigger on Dot (mod module)

**Step 2.1** — In [WorkbenchStore.kt](mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt) `charTyped()`
- After inserting `.` and calling `refreshIde()`, call `openCompletion()` to auto-show module methods
- `refreshIde()` clears completions, so `openCompletion()` must come **after** it

---

### Relevant Files
- [SourceTextSupport.kt](compiler/src/main/kotlin/ck/lang/frontend/SourceTextSupport.kt) — add `importPrefix()`
- [LanguageIde.kt](compiler/src/main/kotlin/ck/lang/frontend/LanguageIde.kt) — add import branch in `complete()`
- [LanguageBuiltins.kt](compiler/src/main/kotlin/ck/lang/frontend/LanguageBuiltins.kt) — read-only reference
- [WorkbenchStore.kt](mod/src/main/kotlin/ck/mod/application/workbench/WorkbenchStore.kt) — auto-trigger on `.`
- [LanguageIdeTest.kt](compiler/src/test/kotlin/ck/lang/frontend/LanguageIdeTest.kt) — new tests

### Verification
1. `./gradlew :compiler:test` — existing tests still pass
2. New tests verify import completion (all modules, partial prefix, multi-import)
3. Manual in-game: `import ` + Ctrl+Space → module list; `terminal.` → auto-popup with methods

### Decisions
- Import completions don't filter already-imported modules (can refine later)
- Auto-trigger only on `.` — not on `import ` or general typing (per your preference)
- Existing `applyCompletion()` / `findIdentifierStart()` already handles both contexts correctly — no changes needed
