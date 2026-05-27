# CKL Format Triggers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add manual Workbench editor triggers for existing CKL Format Document and Cleanup Document actions.

**Architecture:** `WorkbenchStore` owns the behavior. Toolbar buttons and keyboard shortcuts delegate to store methods, and store methods call `WorkbenchIdeFacade` then apply returned `TextEdit`s through the existing local edit / CRDT pipeline.

**Tech Stack:** Kotlin, Gradle, kotlinx-coroutines-test, existing Workbench UI DSL, CKL IDE facade.

---

## File structure

- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/input/KeyCodes.kt`: add missing key/modifier constants for shortcuts.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`: add `formatOpenDocument`, `cleanupOpenDocument`, shared edit application helper, and keyboard shortcut handling.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt`: add `Format` and `Clean` toolbar buttons.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`: add store and shortcut tests; extend `FakeWorkbenchIdeFacade` with configurable format/cleanup results.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilderTest.kt`: strengthen toolbar hit-region expectation for the two extra buttons.

## Task 1: Store format/cleanup actions

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`

- [ ] **Step 1: Write failing store tests**

Add these tests near the existing editor/action tests in `WorkbenchStoreTest`:

```kotlin
@Test
fun formatOpenDocumentAppliesFacadeEdits() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        ideFacade.nextFormatResult =
            ru.lazyhat.compukterkraft.lang.frontend.FormatResult(
                listOf(TextEdit(0, "fun main(){println();}".length, "fun main() {\n    println();\n}\n")),
            )
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = DeviceWorkspaceDocument("main.ck", "fun main(){println();}", 0))

        store.formatOpenDocument(visibleEditorLines = 20)

        assertEquals("fun main() {\n    println();\n}\n", store.state.editor.text)
        assertEquals(listOf("formatDocument:main.ck"), ideFacade.calls.filter { it.startsWith("formatDocument") })
    }

@Test
fun cleanupOpenDocumentAppliesFacadeEdits() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        ideFacade.nextCleanupResult =
            ru.lazyhat.compukterkraft.lang.frontend.FormatResult(
                listOf(TextEdit(0, "import terminal { clear, println };\nfun main(){println();}".length, "import terminal { println };\n\nfun main() {\n    println();\n}\n")),
            )
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = DeviceWorkspaceDocument("main.ck", "import terminal { clear, println };\nfun main(){println();}", 0))

        store.cleanupOpenDocument(visibleEditorLines = 20)

        assertEquals("import terminal { println };\n\nfun main() {\n    println();\n}\n", store.state.editor.text)
        assertEquals(listOf("cleanupDocument:main.ck"), ideFacade.calls.filter { it.startsWith("cleanupDocument") })
    }
```

Extend the existing `FakeWorkbenchIdeFacade` in the same file:

```kotlin
var nextFormatResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(emptyList())
var nextCleanupResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(emptyList())
```

Replace its current `formatDocument` and `cleanupDocument` methods with:

```kotlin
override fun formatDocument(
    path: String,
    source: String,
): ru.lazyhat.compukterkraft.lang.frontend.FormatResult {
    calls += "formatDocument:$path"
    return nextFormatResult
}

override fun cleanupDocument(
    path: String,
    source: String,
): ru.lazyhat.compukterkraft.lang.frontend.FormatResult {
    calls += "cleanupDocument:$path"
    return nextCleanupResult
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*formatOpenDocumentAppliesFacadeEdits' --tests '*WorkbenchStoreTest*cleanupOpenDocumentAppliesFacadeEdits'
```

Expected: compile failure for unresolved `formatOpenDocument` and `cleanupOpenDocument`.

- [ ] **Step 3: Implement store methods**

In `WorkbenchStore`, add public methods near `runTargetProgram` / `rebootComputer` action methods:

```kotlin
fun formatOpenDocument(visibleEditorLines: Int) {
    val document = state.openDocument ?: return
    val result = ideFacade.formatDocument(document.path, state.editor.text)
    applyIdeTextEdits(result.edits, visibleEditorLines)
}

fun cleanupOpenDocument(visibleEditorLines: Int) {
    val document = state.openDocument ?: return
    val result = ideFacade.cleanupDocument(document.path, state.editor.text)
    applyIdeTextEdits(result.edits, visibleEditorLines)
}
```

Add this private helper near existing text-edit helpers:

```kotlin
private fun applyIdeTextEdits(
    edits: List<ru.lazyhat.compukterkraft.lang.runtime.TextEdit>,
    visibleEditorLines: Int,
) {
    if (edits.isEmpty()) return
    closeCompletion()
    edits.sortedByDescending { it.startOffset }.forEach { edit ->
        val currentText = state.editor.text
        val start = edit.startOffset
        val end = edit.endOffset
        if (start < 0 || end < start || end > currentText.length) return@forEach
        if (replica == null) {
            val before = currentText.substring(0, start)
            val after = currentText.substring(end)
            val nextText = before + edit.replacement + after
            val cursorOffset = (start + edit.replacement.length).coerceIn(0, nextText.length)
            val (line, column) = lineColumnAt(nextText, cursorOffset)
            _state.value =
                state.copy(
                    editor =
                        state.editor.copy(
                            text = nextText,
                            cursorLine = line,
                            cursorColumn = column,
                        ).keepCursorVisible(visibleEditorLines),
                )
            refreshIde()
        } else {
            if (end > start) applyLocalEdit(LocalEdit.Delete(start, end - start))
            if (edit.replacement.isNotEmpty()) applyLocalEdit(LocalEdit.Insert(start, edit.replacement))
            _state.value = state.copy(editor = state.editor.keepCursorVisible(visibleEditorLines))
        }
    }
}
```

- [ ] **Step 4: Run targeted tests**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*formatOpenDocumentAppliesFacadeEdits' --tests '*WorkbenchStoreTest*cleanupOpenDocumentAppliesFacadeEdits'
```

Expected: both tests pass.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt
git commit -m "feat(workbench): apply format cleanup edits"
```

## Task 2: Keyboard shortcuts

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/input/KeyCodes.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Write failing shortcut tests**

Add tests to `WorkbenchStoreTest`:

```kotlin
@Test
fun ctrlAltFTriggersFormatDocument() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        ideFacade.nextFormatResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(listOf(TextEdit(0, 1, "formatted")))
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()
        store.bind(backgroundScope, updates)
        updates.push(document = DeviceWorkspaceDocument("main.ck", "x", 0))

        assertTrue(store.keyPressed(KeyCodes.KEY_F, KeyCodes.MOD_CONTROL or KeyCodes.MOD_ALT, visibleEditorLines = 20))

        assertEquals("formatted", store.state.editor.text)
        assertTrue(ideFacade.calls.contains("formatDocument:main.ck"))
    }

@Test
fun ctrlAltLTriggersCleanupDocument() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        ideFacade.nextCleanupResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(listOf(TextEdit(0, 1, "cleaned")))
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()
        store.bind(backgroundScope, updates)
        updates.push(document = DeviceWorkspaceDocument("main.ck", "x", 0))

        assertTrue(store.keyPressed(KeyCodes.KEY_L, KeyCodes.MOD_CONTROL or KeyCodes.MOD_ALT, visibleEditorLines = 20))

        assertEquals("cleaned", store.state.editor.text)
        assertTrue(ideFacade.calls.contains("cleanupDocument:main.ck"))
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*ctrlAltFTriggersFormatDocument' --tests '*WorkbenchStoreTest*ctrlAltLTriggersCleanupDocument'
```

Expected: compile failure for missing `KEY_F`, `KEY_L`, or `MOD_ALT`, or runtime failure because shortcuts are not handled.

- [ ] **Step 3: Add key constants**

In `KeyCodes`, add:

```kotlin
const val KEY_F = 70
const val KEY_L = 76
const val MOD_ALT = 4
```

- [ ] **Step 4: Handle shortcuts**

In `WorkbenchStore.keyPressed`, inside the control-modifier block and before existing cases that might treat the key as editing input, add:

```kotlin
if ((modifiers and KeyCodes.MOD_CONTROL) != 0 && (modifiers and KeyCodes.MOD_ALT) != 0) {
    when (key) {
        KeyCodes.KEY_F -> {
            formatOpenDocument(visibleEditorLines)
            return true
        }

        KeyCodes.KEY_L -> {
            cleanupOpenDocument(visibleEditorLines)
            return true
        }
    }
}
```

- [ ] **Step 5: Run targeted tests**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*ctrlAltFTriggersFormatDocument' --tests '*WorkbenchStoreTest*ctrlAltLTriggersCleanupDocument'
```

Expected: both tests pass.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/input/KeyCodes.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt
git commit -m "feat(workbench): add format cleanup shortcuts"
```

## Task 3: Toolbar buttons

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilderTest.kt`

- [ ] **Step 1: Write failing UI test expectation**

In `WorkbenchUiBuilderTest`, update the toolbar hit-region assertion:

```kotlin
assertTrue(program.hitRegions.size >= 6, "expected ≥6 toolbar/sidebar hit regions after adding Format/Clean, got ${program.hitRegions.size}")
```

- [ ] **Step 2: Run UI builder test to verify failure**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchUiBuilderTest*buildWorkbenchUi compiles into a non-empty ScreenProgram'
```

Expected: failure if the current compiled program has fewer than six hit regions.

- [ ] **Step 3: Add toolbar buttons**

In `buildToolbar`, after `Run` and before the spacer, add:

```kotlin
toolbarButton(
    label = value("Format"),
    enabled = value { store.state.openDocument != null },
    onClick = { store.formatOpenDocument(visibleEditorLines = 20) },
)
toolbarButton(
    label = value("Clean"),
    enabled = value { store.state.openDocument != null },
    onClick = { store.cleanupOpenDocument(visibleEditorLines = 20) },
)
```

Use `20` as the MVP visible-line fallback for toolbar actions because the current toolbar callback does not receive editor viewport metrics. Keyboard-triggered formatting receives the real `visibleEditorLines` from the editor path.

- [ ] **Step 4: Run UI builder test**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchUiBuilderTest*buildWorkbenchUi compiles into a non-empty ScreenProgram'
```

Expected: pass.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilderTest.kt
git commit -m "feat(workbench): add format cleanup toolbar actions"
```

## Task 4: Verification and docs

**Files:**
- Modify if needed: `docs/LANGUAGE.md`

- [ ] **Step 1: Check whether docs need trigger details**

Open `docs/LANGUAGE.md`. If the existing formatting section only documents API behavior, add a short sentence:

```markdown
In the Workbench editor, Format can be triggered from the toolbar or with `Ctrl+Alt+F`; Cleanup can be triggered from the toolbar or with `Ctrl+Alt+L`.
```

- [ ] **Step 2: Run verification**

Run:

```bash
./gradlew :core:test
./gradlew test
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check git status**

Run:

```bash
git status --short
```

Expected: only intentional documentation changes remain, or no changes if docs already covered triggers.

- [ ] **Step 4: Commit docs if changed**

If `docs/LANGUAGE.md` changed, run:

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document format cleanup triggers"
```

Do not create or move git tags unless the user explicitly requests it.
