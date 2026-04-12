# IDE Autocomplete Trigger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the in-game IDE auto-open completion while typing ordinary word prefixes, keep `Ctrl+Space` and `.` triggers, and remove the automatic popup after `import `.

**Architecture:** Keep completion candidate generation in `compiler` and keep popup-trigger behavior in `core`. Add a shared identifier-trigger helper in source text support so `WorkbenchStore` can open completion only in real word contexts, then cover the new trigger rules with focused unit tests in `core`.

**Tech Stack:** Kotlin, Kotlin Test, kotlinx-coroutines-test, Gradle multi-module build (`:compiler`, `:core`)

---

## File Map

| File | Action | Responsibility |
| --- | --- | --- |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt` | Modify | Add helper for identifier-like auto-trigger detection |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt` | Modify | Replace import-space trigger with identifier-like trigger |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt` | Create | Add core-level unit tests for trigger behavior |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupportTest.kt` | Create | Add targeted tests for the new trigger helper |

### Task 1: Add Identifier Trigger Helper in Compiler Layer

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupportTest.kt`

- [ ] **Step 1: Write the failing helper tests**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupportTest.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceTextSupportTest {
    @Test
    fun detectsIdentifierAutoTriggerAtWordEnd() {
        assertTrue(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("wh", 2))
        assertTrue(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("value_n", 7))
    }

    @Test
    fun rejectsImportSpaceSpecialCase() {
        assertFalse(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("import ", 7))
    }

    @Test
    fun rejectsNonIdentifierContexts() {
        assertFalse(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("123", 3))
        assertFalse(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("terminal.", 9))
        assertFalse(SourceTextSupport.shouldAutoTriggerIdentifierCompletion("", 0))
    }
}
```

- [ ] **Step 2: Run the compiler test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.SourceTextSupportTest" --no-daemon`

Expected: FAIL because `shouldAutoTriggerIdentifierCompletion` does not exist yet.

- [ ] **Step 3: Add the minimal helper implementation**

In `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`, add a new helper near `identifierPrefix()`:

```kotlin
    fun shouldAutoTriggerIdentifierCompletion(
        source: String,
        offset: Int,
    ): Boolean {
        val prefix = identifierPrefix(source, offset)
        if (prefix.isEmpty()) return false
        val prefixSource = source.take(offset)
        if (prefixSource.endsWith("import ")) return false
        return prefix.length == prefixSource.takeLastWhile { it == '_' || it.isLetterOrDigit() }.length
    }
```

If this exact expression proves too strict in review, preserve the behavior contract from the tests: non-empty identifier suffix only, no import-space trigger, no dot/member suffix.

- [ ] **Step 4: Re-run the compiler helper test to verify it passes**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.SourceTextSupportTest" --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupportTest.kt
git commit -m "test: add identifier completion trigger helper"
```

### Task 2: Add Core-Level Store Tests for Trigger Behavior

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt`
- Modify later in Task 3: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt`

- [ ] **Step 1: Write the failing store tests**

Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.core.application.workbench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.lang.api.SourceLocation
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchStoreTest {
    @Test
    fun opensCompletionWhenTypingIdentifierPrefix() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            store.charTyped('w', visibleEditorLines = 20)

            assertEquals(listOf("completeFromLastAnalysis:0:1"), ideFacade.calls)
            assertTrue(store.state.editor.completionItems.isNotEmpty())
        }

    @Test
    fun doesNotOpenCompletionAfterImportSpace() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "import", 0))
            store.toggleMode()
            store.moveCursorTo(0, 6, visibleEditorLines = 20)

            store.charTyped(' ', visibleEditorLines = 20)

            assertTrue(ideFacade.calls.isEmpty())
            assertTrue(store.state.editor.completionItems.isEmpty())
        }

    @Test
    fun keepsDotTriggerWorking() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "terminal", 0))
            store.toggleMode()
            store.moveCursorTo(0, 8, visibleEditorLines = 20)

            store.charTyped('.', visibleEditorLines = 20)

            assertEquals(listOf("completeFromLastAnalysis:0:9"), ideFacade.calls)
        }

    private class FakeWorkbenchUpdateSource : WorkbenchUpdateSource {
        private val _stateFlow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = _stateFlow

        fun push(
            entries: List<ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry> = emptyList(),
            document: ComputerWorkspaceDocument? = null,
        ) {
            _stateFlow.value = WorkbenchRemoteState(entries = entries, document = document)
        }
    }

    private class FakeWorkspaceGateway : WorkspaceGateway {
        override fun list(path: String) {}
        override fun read(path: String) {}
        override fun write(path: String, text: String) {}
    }

    private class FakeComputerControlGateway : ComputerControlGateway {
        override fun reboot() {}
    }

    private class FakeWorkbenchIdeFacade : WorkbenchIdeFacade {
        val calls = mutableListOf<String>()

        override fun analyze(path: String, source: String): ComputerIdeSnapshot =
            ComputerIdeSnapshot(
                document = ComputerWorkspaceDocument(path, source, 0),
                diagnostics = emptyList(),
                highlights = emptyList(),
            )

        override fun complete(path: String, source: String, line: Int, column: Int): List<CompletionItem> =
            listOf(CompletionItem(label = "manual", detail = "", kind = CompletionItemKind.KEYWORD))

        override fun completeFromLastAnalysis(path: String, source: String, line: Int, column: Int): List<CompletionItem> {
            calls += "completeFromLastAnalysis:$line:$column"
            return listOf(CompletionItem(label = "while", detail = "keyword", kind = CompletionItemKind.KEYWORD))
        }

        override fun hover(path: String, source: String, line: Int, column: Int): HoverInfo? = null

        override fun definition(path: String, source: String, line: Int, column: Int): DefinitionTarget =
            DefinitionTarget(path, SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0)))
    }
}
```

- [ ] **Step 2: Run the core store test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest" --no-daemon`

Expected: FAIL because current `WorkbenchStore.charTyped()` only auto-opens for `.` and `import `.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt
git commit -m "test: add workbench autocomplete trigger coverage"
```

### Task 3: Replace Import-Space Trigger With Identifier Trigger in WorkbenchStore

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt`
- Uses helper from: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt`
- Verify with: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Update `charTyped()` to use the new trigger rule**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt`, replace the `charTyped()` trigger block and delete `shouldTriggerImportCompletion()`.

Use this implementation shape:

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
            if (shouldOpenCompletionAfterCharTyped(ch)) {
                openCompletionFromCurrentSnapshot()
            }
        }
        return true
    }

    private fun shouldOpenCompletionAfterCharTyped(ch: Char): Boolean {
        if (ch == '.') return true
        if (!(ch == '_' || ch.isLetterOrDigit())) return false
        return SourceTextSupport.shouldAutoTriggerIdentifierCompletion(
            state.editor.text,
            SourceTextSupport.offsetAt(state.editor.text, state.editor.cursorLine, state.editor.cursorColumn),
        )
    }
```

Do not preserve `shouldTriggerImportCompletion()` under a different name. This task removes that special-case behavior.

- [ ] **Step 2: Run the targeted core store tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest" --no-daemon`

Expected: PASS.

- [ ] **Step 3: Run the targeted compiler helper tests again to guard integration assumptions**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.SourceTextSupportTest" --no-daemon`

Expected: PASS.

- [ ] **Step 4: Run the existing language IDE tests to ensure import completion itself still works manually**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest" --no-daemon`

Expected: PASS, including the existing import-completion tests.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupport.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/SourceTextSupportTest.kt
git commit -m "feat: trigger autocomplete on identifiers instead of import space"
```

## Self-Review

### Spec Coverage

- Word-trigger on ordinary prefixes: covered by Tasks 1 and 3.
- Keep `.` trigger: covered by Task 2 and Task 3.
- Remove `import ` auto-popup: covered by Task 2 and Task 3.
- Keep `Ctrl+Space`: indirectly preserved by not touching `keyPressed()`; validated by existing behavior, and can be spot-checked manually after implementation.
- Do not remove manual import completion: protected by Task 3 Step 4.

### Placeholder Scan

No `TODO` or implicit “add tests later” steps remain. Each code-changing step includes exact files, code, and commands.

### Type Consistency

- New helper name is consistently `shouldAutoTriggerIdentifierCompletion`.
- New store helper name is consistently `shouldOpenCompletionAfterCharTyped`.
- Test class names and Gradle commands match the declared file paths.

### Execution Consistency

- `:compiler:test` and `:core:test` are valid Gradle project paths from `settings.gradle.kts`.
- `SourceTextSupport` lives in `compiler`, but `core` depends on `compiler`, so `WorkbenchStore` can call the helper directly.
- New `WorkbenchStoreTest` is placed in `core`, where `WorkbenchStore` itself lives, avoiding duplicated loader-specific tests for this behavior.