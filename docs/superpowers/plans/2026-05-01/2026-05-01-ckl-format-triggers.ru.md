# План реализации триггеров CKL-форматирования

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить ручные триггеры Workbench editor для существующих CKL Format Document и Cleanup Document.

**Architecture:** `WorkbenchStore` владеет поведением. Toolbar buttons и keyboard shortcuts делегируют в методы store, а store вызывает `WorkbenchIdeFacade` и применяет returned `TextEdit` через существующий local edit / CRDT pipeline.

**Tech Stack:** Kotlin, Gradle, kotlinx-coroutines-test, существующий Workbench UI DSL, CKL IDE facade.

---

## Структура файлов

- Изменить `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/input/KeyCodes.kt`: добавить недостающие key/modifier constants для shortcuts.
- Изменить `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`: добавить `formatOpenDocument`, `cleanupOpenDocument`, общий helper применения edits и обработку keyboard shortcuts.
- Изменить `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilder.kt`: добавить toolbar buttons `Format` и `Clean`.
- Изменить `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`: добавить tests для store и shortcuts; расширить `FakeWorkbenchIdeFacade` configurable format/cleanup results.
- Изменить `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/screen/WorkbenchUiBuilderTest.kt`: усилить ожидание toolbar hit regions для двух новых кнопок.

## Task 1: Store format/cleanup actions

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchStore.kt`

- [ ] **Step 1: Написать failing store tests**

Добавить tests рядом с существующими editor/action tests в `WorkbenchStoreTest`:

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

Расширить существующий `FakeWorkbenchIdeFacade` в этом же файле:

```kotlin
var nextFormatResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(emptyList())
var nextCleanupResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult = ru.lazyhat.compukterkraft.lang.frontend.FormatResult(emptyList())
```

Заменить его текущие методы `formatDocument` и `cleanupDocument` на:

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

- [ ] **Step 2: Запустить tests и убедиться, что они падают**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*formatOpenDocumentAppliesFacadeEdits' --tests '*WorkbenchStoreTest*cleanupOpenDocumentAppliesFacadeEdits'
```

Expected: compile failure из-за unresolved `formatOpenDocument` и `cleanupOpenDocument`.

- [ ] **Step 3: Реализовать методы store**

В `WorkbenchStore` добавить public methods рядом с `runTargetProgram` / `rebootComputer` action methods:

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

Добавить private helper рядом с существующими text-edit helpers:

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

- [ ] **Step 4: Запустить targeted tests**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*formatOpenDocumentAppliesFacadeEdits' --tests '*WorkbenchStoreTest*cleanupOpenDocumentAppliesFacadeEdits'
```

Expected: оба tests pass.

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

- [ ] **Step 1: Написать failing shortcut tests**

Добавить tests в `WorkbenchStoreTest`:

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

- [ ] **Step 2: Запустить tests и убедиться, что они падают**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*ctrlAltFTriggersFormatDocument' --tests '*WorkbenchStoreTest*ctrlAltLTriggersCleanupDocument'
```

Expected: compile failure из-за missing `KEY_F`, `KEY_L` или `MOD_ALT`, либо runtime failure потому что shortcuts ещё не обработаны.

- [ ] **Step 3: Добавить key constants**

В `KeyCodes` добавить:

```kotlin
const val KEY_F = 70
const val KEY_L = 76
const val MOD_ALT = 4
```

- [ ] **Step 4: Обработать shortcuts**

В `WorkbenchStore.keyPressed`, внутри control-modifier path и до existing cases, которые могут обработать key как editing input, добавить:

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

- [ ] **Step 5: Запустить targeted tests**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchStoreTest*ctrlAltFTriggersFormatDocument' --tests '*WorkbenchStoreTest*ctrlAltLTriggersCleanupDocument'
```

Expected: оба tests pass.

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

- [ ] **Step 1: Написать failing UI test expectation**

В `WorkbenchUiBuilderTest` обновить toolbar hit-region assertion:

```kotlin
assertTrue(program.hitRegions.size >= 6, "expected ≥6 toolbar/sidebar hit regions after adding Format/Clean, got ${program.hitRegions.size}")
```

- [ ] **Step 2: Запустить UI builder test и убедиться, что он падает**

Run:

```bash
./gradlew :core:test --tests '*WorkbenchUiBuilderTest*buildWorkbenchUi compiles into a non-empty ScreenProgram'
```

Expected: failure, если текущая compiled program содержит меньше шести hit regions.

- [ ] **Step 3: Добавить toolbar buttons**

В `buildToolbar`, после `Run` и перед spacer добавить:

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

Использовать `20` как MVP fallback visible-line для toolbar actions, потому что текущий toolbar callback не получает editor viewport metrics. Keyboard-triggered formatting получает реальный `visibleEditorLines` из editor path.

- [ ] **Step 4: Запустить UI builder test**

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

- [ ] **Step 1: Проверить, нужны ли docs для trigger details**

Открыть `docs/LANGUAGE.md`. Если существующий formatting section документирует только API behavior, добавить короткое предложение:

```markdown
In the Workbench editor, Format can be triggered from the toolbar or with `Ctrl+Alt+F`; Cleanup can be triggered from the toolbar or with `Ctrl+Alt+L`.
```

- [ ] **Step 2: Запустить verification**

Run:

```bash
./gradlew :core:test
./gradlew test
```

Expected: обе команды завершаются с `BUILD SUCCESSFUL`.

- [ ] **Step 3: Проверить git status**

Run:

```bash
git status --short
```

Expected: остаются только intentional documentation changes, либо изменений нет, если docs уже покрывали triggers.

- [ ] **Step 4: Commit docs if changed**

Если `docs/LANGUAGE.md` изменился, run:

```bash
git add docs/LANGUAGE.md
git commit -m "docs(language): document format cleanup triggers"
```

Не создавать и не двигать git tags без явной просьбы пользователя.
